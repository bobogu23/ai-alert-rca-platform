package com.tencent.rca.agent;

import com.tencent.rca.common.enums.EvidenceSourceType;
import com.tencent.rca.config.RcaProperties;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.Evidence;
import com.tencent.rca.domain.LogAnalysisResult;
import com.tencent.rca.domain.LogAnalysisResult.ExceptionCategory;
import com.tencent.rca.fault.RetryExecutor;
import com.tencent.rca.fault.TimeLimiter;
import com.tencent.rca.llm.LlmClient;
import com.tencent.rca.llm.ModelTier;
import com.tencent.rca.logprocess.LogFingerprintService;
import com.tencent.rca.logprocess.StackTracePruner;
import com.tencent.rca.mcp.ClsMcpClient;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 日志分析 Agent (详细设计文档 2.1、5).
 * 流程: CLS 拉取异常日志样例 -> 指纹归一化聚类去重 -> 框架堆栈裁剪 -> LLM 梳理调用链与疑似依赖.
 * 防幻觉: 异常类别、证据引用等事实性数据由本地确定性算法产出, LLM 仅做语义判断 (详细设计文档 4.1).
 * 容错: MCP 调用带重试与超时, 数据源失败或 LLM 失败时降级返回, 不中断整体流程 (详细设计文档 7.4).
 */
@Slf4j
@Component
public class LogAnalysisAgent {

    private static final int SAMPLE_LIMIT = 30;
    private static final int TOP_N = 5;
    private static final int SNIPPET_MAX_LEN = 600;

    private static final String SYSTEM_PROMPT = """
            你是资深 SRE 日志分析专家. 你只能基于给定的日志聚类结果进行分析, 严禁编造未出现的信息.
            任务:
            1) 梳理异常背后的业务调用链;
            2) 判断疑似异常依赖类型, 取值必须为 downstream/db/redis/mq/business/resource/unknown 之一.
            若信息不足以判断, suspectedDependency 返回 unknown.""";

    private final ClsMcpClient clsMcpClient;
    private final LogFingerprintService fingerprintService;
    private final StackTracePruner stackTracePruner;
    private final LlmClient llmClient;
    private final RetryExecutor retryExecutor;
    private final TimeLimiter timeLimiter;
    private final RcaProperties properties;

    public LogAnalysisAgent(ClsMcpClient clsMcpClient,
                            LogFingerprintService fingerprintService,
                            StackTracePruner stackTracePruner,
                            LlmClient llmClient,
                            RetryExecutor retryExecutor,
                            TimeLimiter timeLimiter,
                            RcaProperties properties) {
        this.clsMcpClient = clsMcpClient;
        this.fingerprintService = fingerprintService;
        this.stackTracePruner = stackTracePruner;
        this.llmClient = llmClient;
        this.retryExecutor = retryExecutor;
        this.timeLimiter = timeLimiter;
        this.properties = properties;
    }

    /**
     * 分析指定告警的 CLS 异常日志.
     *
     * @param context 告警上下文
     * @return 日志分析结果 (含降级标记)
     */
    public LogAnalysisResult analyze(AlertContext context) {
        log.info("[LogAnalysisAgent] 输入: service={}, env={}, timeWindow={}~{}",
                context.serviceName(), context.env(),
                context.timeWindow().start(), context.timeWindow().end());
        List<String> rawLogs;
        try {
            rawLogs = fetchLogsWithFaultTolerance(context);
        } catch (RuntimeException ex) {
            log.warn("CLS 日志查询失败, 该维度降级: {}", ex.getMessage());
            return logResult(degraded("CLS 日志数据源查询失败: " + ex.getMessage()));
        }

        List<ExceptionCategory> categories = topCategories(rawLogs);
        if (categories.isEmpty()) {
            log.info("[LogAnalysisAgent] 输出: 无异常聚类, service={}", context.serviceName());
            return new LogAnalysisResult(List.of(), "", "none", List.of(), false, null);
        }

        List<Evidence> evidences = buildEvidences(categories);

        try {
            LogInsight insight = callLlm(categories);
            return logResult(new LogAnalysisResult(
                    categories, insight.prunedCallChain(), insight.suspectedDependency(), evidences, false, null));
        } catch (RuntimeException ex) {
            log.warn("日志已聚类, 但 LLM 调用链梳理失败, 部分降级: {}", ex.getMessage());
            return logResult(new LogAnalysisResult(
                    categories, "", "unknown", evidences, true, "LLM 调用链梳理失败: " + ex.getMessage()));
        }
    }

    private LogAnalysisResult logResult(LogAnalysisResult result) {
        log.info("[LogAnalysisAgent] 输出: 异常类别数={}, 疑似依赖={}, 证据数={}, 降级={}",
                result.topExceptionCategories().size(), result.suspectedDependency(),
                result.evidences().size(), result.degraded());
        return result;
    }

    private List<String> fetchLogsWithFaultTolerance(AlertContext context) {
        // MCP 与 LLM 共用最大重试次数配置; 超时不重试, 直接由上层降级, 避免拖长整体时延
        return retryExecutor.execute(
                () -> timeLimiter.callWithTimeout(
                        () -> clsMcpClient.fetchErrorLogSamples(context, SAMPLE_LIMIT),
                        properties.getTimeout().getMcpCallMs(),
                        "cls-get-log"),
                properties.getLlm().getMaxRetries(),
                "cls-get-log");
    }

    private List<ExceptionCategory> topCategories(List<String> rawLogs) {
        List<ExceptionCategory> all = fingerprintService.cluster(rawLogs);
        return all.size() > TOP_N ? all.subList(0, TOP_N) : all;
    }

    private List<Evidence> buildEvidences(List<ExceptionCategory> categories) {
        List<Evidence> evidences = new ArrayList<>();
        for (ExceptionCategory category : categories) {
            String prunedSnippet = truncate(stackTracePruner.prune(category.sampleLog()));
            String sourceRef = StringUtils.hasText(category.sampleTrace())
                    ? category.sampleTrace() : category.fingerprint();
            String description = String.format(
                    "异常类别出现 %d 次(占比 %.1f%%): %s",
                    category.count(), category.ratio() * 100, category.fingerprint());
            evidences.add(new Evidence(description, EvidenceSourceType.LOG, sourceRef, prunedSnippet));
        }
        return evidences;
    }

    private LogInsight callLlm(List<ExceptionCategory> categories) {
        String userPrompt = buildUserPrompt(categories);
        return llmClient.completeStructured(ModelTier.REASONING, SYSTEM_PROMPT, userPrompt, LogInsight.class);
    }

    private String buildUserPrompt(List<ExceptionCategory> categories) {
        StringBuilder builder = new StringBuilder("以下是聚类去重后的 Top 异常类别 (已裁剪框架堆栈):\n\n");
        int index = 1;
        for (ExceptionCategory category : categories) {
            String prunedSnippet = truncate(stackTracePruner.prune(category.sampleLog()));
            builder.append(String.format(
                    "[类别 %d] 出现 %d 次, 占比 %.1f%%\n指纹: %s\n样例:\n%s\n\n",
                    index++, category.count(), category.ratio() * 100, category.fingerprint(), prunedSnippet));
        }
        return builder.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > SNIPPET_MAX_LEN ? text.substring(0, SNIPPET_MAX_LEN) + "..." : text;
    }

    private LogAnalysisResult degraded(String note) {
        return new LogAnalysisResult(List.of(), "", "unknown", List.of(), true, note);
    }
}
