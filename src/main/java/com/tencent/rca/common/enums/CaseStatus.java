package com.tencent.rca.common.enums;

/**
 * 告警案卷状态机 (详细设计文档 6.1、7.7).
 */
public enum CaseStatus {
    /** 新建, 待分析. */
    OPEN,
    /** 分析中. */
    ANALYZING,
    /** 已产出正常报告. */
    ANALYZED,
    /** 分析失败, 已产出兜底报告. */
    ANALYZE_FAILED,
    /** 人工已确认. */
    ACKNOWLEDGED,
    /** 已解决. */
    RESOLVED,
    /** 误报. */
    FALSE_POSITIVE,
    /** 级联受影响, 已归并到根因案卷. */
    CASCADED
}
