package com.tencent.rca.agent;

/**
 * TraceMetricAgent 中 LLM 负责的性能研判输出契约.
 * 慢 span、指标原始数据等事实性内容由本地提取, LLM 仅产出以下语义结论.
 *
 * @param bottleneckType 性能瓶颈类型, 取值限定为
 *                       local_code/db/redis/downstream/gc/resource/unknown
 * @param metricSummary  关键指标变化的一句话概括 (当前值 vs 基线)
 */
public record TraceInsight(
        String bottleneckType,
        String metricSummary) {
}
