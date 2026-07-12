package com.tencent.rca.common.enums;

/**
 * 证据来源类型. HISTORY_CASE 为预留类型, 第一版不启用历史相似案例检索 (详细设计文档 6.4).
 */
public enum EvidenceSourceType {
    /** 来自 CLS 日志. */
    LOG,
    /** 来自伽利略 Trace. */
    TRACE,
    /** 来自伽利略 Metric. */
    METRIC,
    /** 来自代码仓库. */
    CODE,
    /** 预留: 来自历史相似案例. */
    HISTORY_CASE
}
