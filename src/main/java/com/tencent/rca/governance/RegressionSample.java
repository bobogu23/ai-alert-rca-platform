package com.tencent.rca.governance;

/**
 * 回归集样本 (在线自我完善方案 07 文档 3.3, 对齐 06 测评方案 §4.3 标注 Schema).
 * 由高可信 badcase 反馈脱敏后导出, 作为防回归测试用例: 记录 "系统当时错误诊断" 与 "人工标注真实根因".
 *
 * @param sampleId         样本 ID (regression-{feedbackId})
 * @param sourceFeedbackId 来源反馈 ID
 * @param fingerprint      告警指纹 (已脱敏无关联)
 * @param serviceName      服务名
 * @param aiRootCauseSummary 系统当时误判的根因 (已脱敏)
 * @param aiConfidence     系统当时置信度
 * @param actualDirection  人工标注的真实根因方向
 * @param actualRootCauseSummary 人工标注的真实根因描述 (已脱敏)
 * @param correctEvidenceHint 人工给出的正确证据线索 (已脱敏)
 * @param errorCategory    错误类型
 * @param credibility      反馈可信度分
 */
public record RegressionSample(
        String sampleId,
        Long sourceFeedbackId,
        String fingerprint,
        String serviceName,
        String aiRootCauseSummary,
        String aiConfidence,
        String actualDirection,
        String actualRootCauseSummary,
        String correctEvidenceHint,
        String errorCategory,
        double credibility) {
}
