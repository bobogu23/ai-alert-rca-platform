package com.tencent.rca.agent;

import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.domain.CriticResult;
import com.tencent.rca.domain.Evidence;
import com.tencent.rca.domain.EvidenceBundle;
import com.tencent.rca.domain.ReasoningResult;
import com.tencent.rca.domain.RootCauseHypothesis;
import com.tencent.rca.llm.LlmClient;
import com.tencent.rca.llm.ModelTier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 反幻觉自校验 Agent (详细设计文档 4.3、4.4).
 * 校验分两级: 程序化硬校验优先(事实性、可用代码确定的判断绝不交给 LLM), LLM 语义校验兜底.
 * 硬校验项: (1) 支撑证据 sourceRef 必须真实存在于证据集合; (2) 置信度与证据数量匹配, 单一证据不得标 HIGH;
 * (3) 逻辑冲突检测, 不允许出现多个互斥的 HIGH 假设.
 * LLM 语义校验只核对假设是否曲解证据原意.
 * LLM 调用异常不在此吞没, 交由 Orchestrator 按容错策略处理 (详细设计文档 7.4).
 */
@Slf4j
@Component
public class CriticAgent {

    private static final String SYSTEM_PROMPT = """
            你是严谨的根因结论审查员. 你只做语义一致性判断, 不做事实性校验.
            给定若干根因假设及其引用证据的原始片段, 请判断: 假设的表述是否曲解、夸大或臆断了证据的原始含义.
            仅当发现明确曲解时才判定不一致. 输出:
            - consistent: 语义是否一致(true/false);
            - issues: 若不一致, 列出每条具体问题(指明是哪个假设曲解了哪条证据), 一致则为空.""";

    private final LlmClient llmClient;

    public CriticAgent(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 对根因推理结果做反幻觉自校验.
     *
     * @param reasoning 根因推理结果
     * @param bundle    证据集合, 用于校验证据引用真伪
     * @return 校验结论, 未通过时携带可操作的打回原因
     */
    public CriticResult verify(ReasoningResult reasoning, EvidenceBundle bundle) {
        List<String> rejectReasons = new ArrayList<>();
        List<RootCauseHypothesis> hypotheses = reasoning.hypotheses() == null
                ? List.of() : reasoning.hypotheses();
        log.info("[CriticAgent] 输入: 假设数={}, 证据总数={}",
                hypotheses.size(), bundle.allEvidences().size());

        checkEvidenceExistence(hypotheses, bundle, rejectReasons);
        checkConfidenceEvidenceMatch(hypotheses, rejectReasons);
        checkConflict(hypotheses, rejectReasons);

        // 硬校验已发现问题时直接打回, 避免浪费 LLM 调用; 硬校验通过再做 LLM 语义兜底
        if (!rejectReasons.isEmpty()) {
            return logResult(new CriticResult(false, rejectReasons));
        }
        if (!hypotheses.isEmpty()) {
            rejectReasons.addAll(semanticCheck(hypotheses));
        }
        return logResult(new CriticResult(rejectReasons.isEmpty(), rejectReasons));
    }

    private CriticResult logResult(CriticResult result) {
        log.info("[CriticAgent] 输出: passed={}, 打回原因数={}",
                result.passed(), result.rejectReasons().size());
        return result;
    }

    private void checkEvidenceExistence(List<RootCauseHypothesis> hypotheses,
                                        EvidenceBundle bundle,
                                        List<String> rejectReasons) {
        Set<String> validRefs = new HashSet<>();
        for (Evidence evidence : bundle.allEvidences()) {
            if (StringUtils.hasText(evidence.sourceRef())) {
                validRefs.add(evidence.sourceRef());
            }
        }
        for (int i = 0; i < hypotheses.size(); i++) {
            RootCauseHypothesis hypothesis = hypotheses.get(i);
            List<Evidence> supporting = hypothesis.supportingEvidence();
            if (supporting == null) {
                continue;
            }
            for (Evidence evidence : supporting) {
                if (!validRefs.contains(evidence.sourceRef())) {
                    rejectReasons.add(String.format(
                            "假设 %d 引用了不存在的证据(sourceRef=%s), 疑似编造, 请剔除该引用或重新推理",
                            i + 1, evidence.sourceRef()));
                }
            }
        }
    }

    private void checkConfidenceEvidenceMatch(List<RootCauseHypothesis> hypotheses, List<String> rejectReasons) {
        for (int i = 0; i < hypotheses.size(); i++) {
            RootCauseHypothesis hypothesis = hypotheses.get(i);
            int evidenceCount = hypothesis.supportingEvidence() == null ? 0 : hypothesis.supportingEvidence().size();
            if (hypothesis.confidence() == ConfidenceLevel.HIGH && evidenceCount < 2) {
                rejectReasons.add(String.format(
                        "假设 %d 仅有 %d 条支撑证据却标注 HIGH, 置信度与证据不匹配, 请降级至 MEDIUM 或补充独立证据",
                        i + 1, evidenceCount));
            }
        }
    }

    private void checkConflict(List<RootCauseHypothesis> hypotheses, List<String> rejectReasons) {
        long highCount = hypotheses.stream()
                .filter(hypothesis -> hypothesis.confidence() == ConfidenceLevel.HIGH)
                .count();
        if (highCount > 1) {
            rejectReasons.add(String.format(
                    "同时存在 %d 个 HIGH 置信度假设, 根因结论互斥冲突, 请仅保留一个主根因为 HIGH, 其余降级",
                    highCount));
        }
    }

    private List<String> semanticCheck(List<RootCauseHypothesis> hypotheses) {
        String userPrompt = buildSemanticPrompt(hypotheses);
        CriticLlmOutput output = llmClient.completeStructured(
                ModelTier.REASONING, SYSTEM_PROMPT, userPrompt, CriticLlmOutput.class);
        if (output.consistent()) {
            return List.of();
        }
        return output.issues() == null ? List.of() : output.issues();
    }

    private String buildSemanticPrompt(List<RootCauseHypothesis> hypotheses) {
        StringBuilder builder = new StringBuilder("待审查的根因假设及其引用证据:\n\n");
        for (int i = 0; i < hypotheses.size(); i++) {
            RootCauseHypothesis hypothesis = hypotheses.get(i);
            builder.append("假设 ").append(i + 1).append(": ").append(hypothesis.summary())
                    .append(" (置信度 ").append(hypothesis.confidence()).append(")\n");
            List<Evidence> supporting = hypothesis.supportingEvidence();
            if (supporting != null) {
                for (Evidence evidence : supporting) {
                    builder.append("  - 证据描述: ").append(evidence.description()).append('\n')
                            .append("    原始片段: ").append(truncate(evidence.rawSnippet())).append('\n');
                }
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        int maxLen = 400;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
