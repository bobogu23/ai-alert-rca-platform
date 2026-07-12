package com.tencent.rca.agent;

import com.tencent.rca.common.enums.EvidenceSourceType;
import com.tencent.rca.config.RcaProperties;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.Evidence;
import com.tencent.rca.domain.TraceMetricResult;
import com.tencent.rca.fault.RetryExecutor;
import com.tencent.rca.fault.TimeLimiter;
import com.tencent.rca.llm.LlmClient;
import com.tencent.rca.llm.ModelTier;
import com.tencent.rca.mcp.GalileoMcpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Trace/Metric 分析 Agent (详细设计文档 2.1、7.4).
 * 流程: 伽利略拉取 Metric 与慢 Trace -> 本地提取慢 span 与指标摘要 -> LLM 研判性能瓶颈类型.
 * 防幻觉: 慢 span、证据引用等事实性数据由本地提取, LLM 仅做瓶颈类型语义研判 (详细设计文档 4.1).
 * 容错: 两路数据源各自带重试与超时, 任一可用即产出证据, 全部失败或 LLM 失败时降级.
 */
@Slf4j
@Component
public class TraceMetricAgent {

    private static final int TEXT_MAX_LEN = 1500;

    private static final String SYSTEM_PROMPT = """
            你是资深 SRE 性能分析专家. 你只能基于给定的指标与 Trace 数据进行分析, 严禁编造未出现的信息.
            任务:
            1) 判断性能瓶颈类型, 取值必须为 local_code/db/redis/downstream/gc/resource/unknown 之一;
            2) 用一句话概括关键指标变化(当前值 vs 基线).
            数据不足以判断时, bottleneckType 返回 unknown.""";

    private final GalileoMcpClient galileoMcpClient;
    private final LlmClient llmClient;
    private final RetryExecutor retryExecutor;
    private final TimeLimiter timeLimiter;
    private final RcaProperties properties;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public TraceMetricAgent(GalileoMcpClient galileoMcpClient,
                            LlmClient llmClient,
                            RetryExecutor retryExecutor,
                            TimeLimiter timeLimiter,
                            RcaProperties properties) {
        this.galileoMcpClient = galileoMcpClient;
        this.llmClient = llmClient;
        this.retryExecutor = retryExecutor;
        this.timeLimiter = timeLimiter;
        this.properties = properties;
    }

    /**
     * 分析指定告警的 Metric 与慢 Trace, 定位性能瓶颈.
     *
     * @param context 告警上下文
     * @return Trace/Metric 分析结果 (含降级标记)
     */
    public TraceMetricResult analyze(AlertContext context) {
        log.info("[TraceMetricAgent] 输入: service={}, env={}, timeWindow={}~{}",
                context.serviceName(), context.env(),
                context.timeWindow().start(), context.timeWindow().end());
        // 两路数据源无依赖, 并行 fan-out 后各自 join, 将耗时从"两次相加"降为"取较大者"
        Future<Map<String, Object>> metricsFuture =
                virtualThreadExecutor.submit(() -> safeFetch(() -> galileoMcpClient.fetchMetrics(context), "galileo-metric"));
        Future<Map<String, Object>> tracesFuture =
                virtualThreadExecutor.submit(() -> safeFetch(() -> galileoMcpClient.fetchSlowTraces(context), "galileo-trace"));
        Map<String, Object> metrics = join(metricsFuture, "galileo-metric");
        Map<String, Object> traces = join(tracesFuture, "galileo-trace");

        if (metrics == null && traces == null) {
            log.warn("伽利略 Metric 与 Trace 均查询失败, 该维度降级");
            return logResult(degraded("伽利略 Trace/Metric 数据源查询失败"));
        }

        List<String> topSlowSpans = extractSlowSpans(traces);
        List<Evidence> evidences = buildEvidences(context, metrics, traces, topSlowSpans);

        try {
            TraceInsight insight = callLlm(metrics, traces);
            return logResult(new TraceMetricResult(
                    insight.bottleneckType(), insight.metricSummary(), topSlowSpans, evidences, false, null));
        } catch (RuntimeException ex) {
            log.warn("指标已获取, 但 LLM 瓶颈研判失败, 部分降级: {}", ex.getMessage());
            return logResult(new TraceMetricResult(
                    "unknown", "", topSlowSpans, evidences, true, "LLM 瓶颈研判失败: " + ex.getMessage()));
        }
    }

    private TraceMetricResult logResult(TraceMetricResult result) {
        log.info("[TraceMetricAgent] 输出: 瓶颈类型={}, 慢span数={}, 证据数={}, 降级={}",
                result.bottleneckType(), result.topSlowSpans().size(),
                result.evidences().size(), result.degraded());
        return result;
    }

    private Map<String, Object> join(Future<Map<String, Object>> future, String name) {
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

    private Map<String, Object> safeFetch(Supplier<Map<String, Object>> call, String name) {
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

    @SuppressWarnings("unchecked")
    private List<String> extractSlowSpans(Map<String, Object> traces) {
        List<String> spans = new ArrayList<>();
        if (traces == null) {
            return spans;
        }
        Object obj = traces.get("spans");
        if (obj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> spanMap && spanMap.get("span_name") != null) {
                    spans.add(String.valueOf(spanMap.get("span_name")));
                } else {
                    spans.add(String.valueOf(item));
                }
            }
        }
        return spans;
    }

    private List<Evidence> buildEvidences(AlertContext context,
                                          Map<String, Object> metrics,
                                          Map<String, Object> traces,
                                          List<String> topSlowSpans) {
        List<Evidence> evidences = new ArrayList<>();
        if (metrics != null) {
            evidences.add(new Evidence(
                    "服务关键指标(耗时/错误率/QPS)采集成功",
                    EvidenceSourceType.METRIC,
                    "rpc.server.duration@" + context.serviceName(),
                    truncate(String.valueOf(metrics))));
        }
        if (traces != null) {
            evidences.add(new Evidence(
                    "慢 Trace 采样成功, Top 慢 span: " + String.join(", ", topSlowSpans),
                    EvidenceSourceType.TRACE,
                    "slow-traces@" + context.serviceName(),
                    truncate(String.valueOf(traces))));
        }
        return evidences;
    }

    private TraceInsight callLlm(Map<String, Object> metrics, Map<String, Object> traces) {
        String userPrompt = "指标数据:\n" + truncate(String.valueOf(metrics))
                + "\n\n慢 Trace 数据:\n" + truncate(String.valueOf(traces));
        return llmClient.completeStructured(ModelTier.REASONING, SYSTEM_PROMPT, userPrompt, TraceInsight.class);
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > TEXT_MAX_LEN ? text.substring(0, TEXT_MAX_LEN) + "..." : text;
    }

    private TraceMetricResult degraded(String note) {
        return new TraceMetricResult("unknown", "", List.of(), List.of(), true, note);
    }
}
