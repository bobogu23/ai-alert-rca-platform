package com.tencent.rca.agent;

import com.tencent.rca.common.enums.EvidenceSourceType;
import com.tencent.rca.config.RcaProperties;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.CodeContextResult;
import com.tencent.rca.domain.Evidence;
import com.tencent.rca.fault.RetryExecutor;
import com.tencent.rca.fault.TimeLimiter;
import com.tencent.rca.llm.LlmClient;
import com.tencent.rca.llm.ModelTier;
import com.tencent.rca.mcp.CodeRepoMcpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 代码上下文 Agent (详细设计文档 2.1、7.4).
 * 流程: 按服务名检索代码片段与最近发布 -> LLM 识别配置隐患点.
 * 第一版以服务名作为检索线索, 后续可由 Orchestrator 按需传入更精确的类名/方法/SQL 线索 (详细设计文档 5.6).
 * 防幻觉: 代码片段、发布记录等事实性数据由本地从 MCP 获取, LLM 仅做隐患研判 (详细设计文档 4.1).
 * 容错: 两路数据源各自带重试与超时, 任一可用即产出证据, 全部失败或 LLM 失败时降级.
 */
@Slf4j
@Component
public class CodeContextAgent {

    private static final int SEARCH_LIMIT = 8;
    private static final int SNIPPET_MAX_LEN = 1500;

    private static final String SYSTEM_PROMPT = """
            你是资深 Java 架构师. 你只能基于给定的代码片段与最近发布记录进行分析, 严禁编造未出现的信息.
            任务: 识别可能导致故障的配置隐患点, 例如 RPC/HTTP 超时或重试或熔断缺失、DB/Redis 连接池过小、
            慢 SQL 或 N+1 查询、缺少缓存、循环内远程调用、事务范围过大、线程池过小等.
            每条隐患用简短一句话描述. 无法识别时返回空列表.""";

    private final CodeRepoMcpClient codeRepoMcpClient;
    private final LlmClient llmClient;
    private final RetryExecutor retryExecutor;
    private final TimeLimiter timeLimiter;
    private final RcaProperties properties;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CodeContextAgent(CodeRepoMcpClient codeRepoMcpClient,
                            LlmClient llmClient,
                            RetryExecutor retryExecutor,
                            TimeLimiter timeLimiter,
                            RcaProperties properties) {
        this.codeRepoMcpClient = codeRepoMcpClient;
        this.llmClient = llmClient;
        this.retryExecutor = retryExecutor;
        this.timeLimiter = timeLimiter;
        this.properties = properties;
    }

    /**
     * 定位与告警服务相关的代码上下文与配置隐患.
     *
     * @param context 告警上下文
     * @return 代码上下文结果 (含降级标记)
     */
    public CodeContextResult analyze(AlertContext context) {
        log.info("[CodeContextAgent] 输入: service={}, env={}", context.serviceName(), context.env());
        String keyword = context.serviceName();
        // 两路数据源无依赖, 并行 fan-out 后各自 join, 将耗时从"两次相加"降为"取较大者"
        Future<List<String>> codeFuture = virtualThreadExecutor.submit(
                () -> safeFetch(() -> codeRepoMcpClient.searchCode(keyword, SEARCH_LIMIT), "coderepo-search"));
        Future<List<String>> releaseFuture = virtualThreadExecutor.submit(
                () -> safeFetch(() -> codeRepoMcpClient.fetchRecentReleases(context.serviceName()), "coderepo-releases"));
        List<String> codeSnippets = join(codeFuture, "coderepo-search");
        List<String> recentChanges = join(releaseFuture, "coderepo-releases");

        if (codeSnippets == null && recentChanges == null) {
            log.warn("代码检索与发布记录查询均失败, 该维度降级");
            return logResult(degraded("代码仓库数据源查询失败"));
        }

        List<String> snippets = codeSnippets != null ? codeSnippets : List.of();
        List<String> changes = recentChanges != null ? recentChanges : List.of();
        if (snippets.isEmpty() && changes.isEmpty()) {
            return logResult(new CodeContextResult(List.of(), List.of(), List.of(), List.of(), false, null));
        }

        List<Evidence> evidences = buildEvidences(context, snippets, changes);

        try {
            CodeInsight insight = callLlm(snippets, changes);
            List<String> riskPoints = insight.riskPoints() != null ? insight.riskPoints() : List.of();
            return logResult(new CodeContextResult(snippets, changes, riskPoints, evidences, false, null));
        } catch (RuntimeException ex) {
            log.warn("代码上下文已获取, 但 LLM 隐患识别失败, 部分降级: {}", ex.getMessage());
            return logResult(new CodeContextResult(
                    snippets, changes, List.of(), evidences, true, "LLM 配置隐患识别失败: " + ex.getMessage()));
        }
    }

    private CodeContextResult logResult(CodeContextResult result) {
        log.info("[CodeContextAgent] 输出: 代码片段数={}, 发布记录数={}, 隐患点数={}, 证据数={}, 降级={}",
                result.codeSnippets().size(), result.recentChanges().size(),
                result.riskPoints().size(), result.evidences().size(), result.degraded());
        return result;
    }

    private List<String> join(Future<List<String>> future, String name) {
        try {
            return future.get();
        } catch (ExecutionException ex) {
            log.warn("{} 并行任务执行异常: {}", name, ex.getMessage());
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("{} 并行任务被中断", name);
            return null;
        }
    }

    private List<String> safeFetch(Supplier<List<String>> call, String name) {
        try {
            return retryExecutor.execute(
                    () -> timeLimiter.callWithTimeout(call::get, properties.getTimeout().getMcpCallMs(), name),
                    properties.getLlm().getMaxRetries(),
                    name);
        } catch (RuntimeException ex) {
            log.warn("{} 查询失败: {}", name, ex.getMessage());
            return null;
        }
    }

    private List<Evidence> buildEvidences(AlertContext context, List<String> snippets, List<String> changes) {
        List<Evidence> evidences = new ArrayList<>();
        if (!snippets.isEmpty()) {
            evidences.add(new Evidence(
                    "检索到 " + snippets.size() + " 处相关代码片段",
                    EvidenceSourceType.CODE,
                    "search:" + context.serviceName(),
                    truncate(String.join("\n", snippets))));
        }
        if (!changes.isEmpty()) {
            evidences.add(new Evidence(
                    "查询到 " + changes.size() + " 条最近发布/变更记录",
                    EvidenceSourceType.CODE,
                    "releases:" + context.serviceName(),
                    truncate(String.join("\n", changes))));
        }
        return evidences;
    }

    private CodeInsight callLlm(List<String> snippets, List<String> changes) {
        String userPrompt = "相关代码片段:\n" + truncate(String.join("\n", snippets))
                + "\n\n最近发布/变更记录:\n" + truncate(String.join("\n", changes));
        return llmClient.completeStructured(ModelTier.REASONING, SYSTEM_PROMPT, userPrompt, CodeInsight.class);
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > SNIPPET_MAX_LEN ? text.substring(0, SNIPPET_MAX_LEN) + "..." : text;
    }

    private CodeContextResult degraded(String note) {
        return new CodeContextResult(List.of(), List.of(), List.of(), List.of(), true, note);
    }
}
