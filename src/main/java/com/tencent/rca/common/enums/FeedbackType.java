package com.tencent.rca.common.enums;

/**
 * 人工反馈类型 (在线自我完善方案 07 文档 2.2).
 * 对应开发/SRE 对归因报告的四种确认结果, 驱动经验飞轮的反馈采集环节.
 */
public enum FeedbackType {
    /** 完全正确: 诊断根因与实际一致. */
    CONFIRMED,
    /** 部分正确: 只诊断出部分根因, 需补充遗漏方向. */
    PARTIAL,
    /** 诊断有误: 根因方向错误, 需给出真实根因与纠错线索. */
    REJECTED,
    /** 误报: 该告警无需归因分析. */
    FALSE_POSITIVE
}
