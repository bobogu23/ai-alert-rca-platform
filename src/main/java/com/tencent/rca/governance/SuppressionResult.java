package com.tencent.rca.governance;

/**
 * 抑制判定结果 (详细设计文档 6.2).
 *
 * @param suppressed 是否被抑制
 * @param ruleType   命中的抑制规则类型(未抑制时为空)
 */
public record SuppressionResult(
        boolean suppressed,
        String ruleType) {
}
