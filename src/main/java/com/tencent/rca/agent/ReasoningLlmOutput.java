package com.tencent.rca.agent;

import java.util.List;

/**
 * RootCauseReasoningAgent 中 LLM 的原始输出契约.
 * LLM 只能引用给定的证据编号(如 E1、E2), 本地再将编号映射回真实 Evidence 对象, 防止编造证据 (详细设计文档 4.1).
 *
 * @param hypotheses  根因假设草稿列表, 按可能性排序
 * @param impactScope 影响范围描述
 */
public record ReasoningLlmOutput(
        List<HypothesisDraft> hypotheses,
        String impactScope) {

    /**
     * 单个根因假设草稿.
     *
     * @param summary               一句话根因
     * @param confidence            置信度字符串 (HIGH/MEDIUM/LOW)
     * @param evidenceRefs          支撑证据编号列表 (只能引用给定编号)
     * @param counterEvidenceOrGaps 反证或未确认项
     */
    public record HypothesisDraft(
            String summary,
            String confidence,
            List<String> evidenceRefs,
            List<String> counterEvidenceOrGaps) {
    }
}
