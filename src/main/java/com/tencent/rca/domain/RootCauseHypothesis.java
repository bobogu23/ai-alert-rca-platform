package com.tencent.rca.domain;

import com.tencent.rca.common.enums.ConfidenceLevel;
import java.util.List;

/**
 * 单个根因假设 (详细设计文档 2.3).
 *
 * @param summary                根因一句话描述
 * @param confidence             置信度
 * @param supportingEvidence     支持证据
 * @param counterEvidenceOrGaps  反证或未确认项
 */
public record RootCauseHypothesis(
        String summary,
        ConfidenceLevel confidence,
        List<Evidence> supportingEvidence,
        List<String> counterEvidenceOrGaps) {
}
