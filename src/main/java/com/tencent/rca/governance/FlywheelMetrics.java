package com.tencent.rca.governance;

/**
 * 经验飞轮健康度指标 (在线自我完善方案 07 文档 6.2).
 *
 * @param windowDays        统计窗口天数
 * @param totalFeedback     反馈总数
 * @param confirmedCount    完全正确反馈数
 * @param partialCount      部分正确反馈数
 * @param rejectedCount     驳回反馈数
 * @param falsePositiveCount 误报反馈数
 * @param adoptionRate      采纳率 (CONFIRMED / 总反馈), 无反馈时为 0
 */
public record FlywheelMetrics(
        int windowDays,
        long totalFeedback,
        long confirmedCount,
        long partialCount,
        long rejectedCount,
        long falsePositiveCount,
        double adoptionRate) {
}
