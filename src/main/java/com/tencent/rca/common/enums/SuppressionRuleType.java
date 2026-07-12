package com.tencent.rca.common.enums;

/**
 * 告警抑制规则类型 (对应 suppression_rule.rule_type, 详细设计文档 6.2).
 */
public enum SuppressionRuleType {
    /** 静默时间窗. */
    TIME_WINDOW,
    /** 维护窗. */
    MAINTENANCE,
    /** 级联屏蔽. */
    CASCADE,
    /** 依赖屏蔽. */
    DEPENDENCY,
    /** 人工静默. */
    MANUAL_SILENCE
}
