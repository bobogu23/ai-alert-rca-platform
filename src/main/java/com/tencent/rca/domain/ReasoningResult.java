package com.tencent.rca.domain;

import java.util.List;

/**
 * 根因推理 Agent 的输出, 按置信度排序的根因假设列表.
 *
 * @param hypotheses 根因假设列表, 第一个为最可能根因
 * @param impactScope 影响范围描述
 */
public record ReasoningResult(
        List<RootCauseHypothesis> hypotheses,
        String impactScope) {
}
