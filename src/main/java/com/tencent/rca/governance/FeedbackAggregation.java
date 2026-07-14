package com.tencent.rca.governance;

import java.util.Map;

/**
 * 反馈聚合分析结果 (在线自我完善方案 07 文档 3.2).
 * 按多维度切分反馈, 定位系统最薄弱的能力维度与高频 badcase, 驱动经验沉淀优先级.
 *
 * @param windowDays          统计窗口天数
 * @param byErrorCategory     按错误类型的计数 (键为 FeedbackErrorCategory 名, 空类型归入 UNSPECIFIED)
 * @param byActualDirection   按真实根因方向的计数 (键为 SuspectedCause 名)
 * @param byService           按服务名的驳回/部分正确计数
 * @param topBadcaseFingerprints 反复被驳回的告警指纹 -> 次数 (高频优先沉淀)
 */
public record FeedbackAggregation(
        int windowDays,
        Map<String, Long> byErrorCategory,
        Map<String, Long> byActualDirection,
        Map<String, Long> byService,
        Map<String, Long> topBadcaseFingerprints) {
}
