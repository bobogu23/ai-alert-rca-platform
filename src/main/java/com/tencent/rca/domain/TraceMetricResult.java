package com.tencent.rca.domain;

import java.util.List;

/**
 * Trace/Metric 分析 Agent 的结构化输出.
 *
 * @param bottleneckType 瓶颈类型 (本地代码/DB/Redis/下游/GC/资源)
 * @param metricSummary  关键指标对比摘要 (当前值 vs 基线)
 * @param topSlowSpans   Top 慢 span 描述
 * @param evidences      产出的证据列表
 * @param degraded       是否因数据源失败而降级
 * @param degradeNote    降级说明
 */
public record TraceMetricResult(
        String bottleneckType,
        String metricSummary,
        List<String> topSlowSpans,
        List<Evidence> evidences,
        boolean degraded,
        String degradeNote) {
}
