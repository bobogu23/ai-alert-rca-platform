package com.tencent.rca.common.enums;

/**
 * 反馈错误类型分类 (在线自我完善方案 07 文档 2.4).
 * 驳回/部分正确时选填, 用于把错误归因到具体能力维度, 指导后续经验沉淀方向.
 */
public enum FeedbackErrorCategory {
    /** 根因方向完全错 (指向推理/取证转向能力). */
    WRONG_DIRECTION,
    /** 只诊断出部分 (指向推理/路由能力). */
    INCOMPLETE,
    /** 引用证据不当 (指向推理能力). */
    WRONG_EVIDENCE,
    /** Agent 激活集不对 (指向意图理解能力). */
    MISSED_ROUTING,
    /** 置信度虚高 (指向自校验能力). */
    OVER_CONFIDENT,
    /** 编造了证据/结论 (指向反幻觉能力). */
    HALLUCINATION
}
