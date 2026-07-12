package com.tencent.rca.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.common.enums.EvidenceSourceType;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.CriticResult;
import com.tencent.rca.domain.Evidence;
import com.tencent.rca.domain.EvidenceBundle;
import com.tencent.rca.domain.LogAnalysisResult;
import com.tencent.rca.domain.ReasoningResult;
import com.tencent.rca.domain.RootCauseHypothesis;
import com.tencent.rca.llm.LlmClient;
import com.tencent.rca.llm.ModelTier;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CriticAgent 单元测试: 校验三类硬校验优先与 LLM 语义兜底. LlmClient 全程 mock.
 */
@ExtendWith(MockitoExtension.class)
class CriticAgentTest {

    @Mock
    private LlmClient llmClient;

    private CriticAgent criticAgent;

    @BeforeEach
    void setUp() {
        criticAgent = new CriticAgent(llmClient);
    }

    @Test
    void shouldRejectWhenEvidenceRefNotExist() {
        Evidence real = evidence("real-ref");
        EvidenceBundle bundle = bundleWith(real);
        // 假设引用了不存在于证据集合的 sourceRef
        Evidence fake = evidence("fake-ref");
        ReasoningResult reasoning = reasoning(hypothesis(ConfidenceLevel.LOW, List.of(fake)));

        CriticResult result = criticAgent.verify(reasoning, bundle);

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectReasons()).anyMatch(r -> r.contains("不存在的证据"));
        verifyNoInteractions(llmClient);
    }

    @Test
    void shouldRejectWhenHighConfidenceWithSingleEvidence() {
        Evidence e1 = evidence("ref1");
        EvidenceBundle bundle = bundleWith(e1);
        ReasoningResult reasoning = reasoning(hypothesis(ConfidenceLevel.HIGH, List.of(e1)));

        CriticResult result = criticAgent.verify(reasoning, bundle);

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectReasons()).anyMatch(r -> r.contains("HIGH"));
        verifyNoInteractions(llmClient);
    }

    @Test
    void shouldRejectWhenMultipleHighHypothesesConflict() {
        Evidence e1 = evidence("ref1");
        Evidence e2 = evidence("ref2");
        EvidenceBundle bundle = bundleWith(e1, e2);
        ReasoningResult reasoning = reasoning(
                hypothesis(ConfidenceLevel.HIGH, List.of(e1, e2)),
                hypothesis(ConfidenceLevel.HIGH, List.of(e1, e2)));

        CriticResult result = criticAgent.verify(reasoning, bundle);

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectReasons()).anyMatch(r -> r.contains("互斥"));
    }

    @Test
    void shouldPassWhenHardChecksOkAndLlmConsistent() {
        Evidence e1 = evidence("ref1");
        Evidence e2 = evidence("ref2");
        EvidenceBundle bundle = bundleWith(e1, e2);
        ReasoningResult reasoning = reasoning(hypothesis(ConfidenceLevel.HIGH, List.of(e1, e2)));
        when(llmClient.completeStructured(eq(ModelTier.REASONING), any(), any(), eq(CriticLlmOutput.class)))
                .thenReturn(new CriticLlmOutput(true, List.of()));

        CriticResult result = criticAgent.verify(reasoning, bundle);

        assertThat(result.passed()).isTrue();
        assertThat(result.rejectReasons()).isEmpty();
    }

    @Test
    void shouldRejectWhenLlmReportsSemanticIssue() {
        Evidence e1 = evidence("ref1");
        EvidenceBundle bundle = bundleWith(e1);
        ReasoningResult reasoning = reasoning(hypothesis(ConfidenceLevel.MEDIUM, List.of(e1)));
        when(llmClient.completeStructured(eq(ModelTier.REASONING), any(), any(), eq(CriticLlmOutput.class)))
                .thenReturn(new CriticLlmOutput(false, List.of("假设 1 曲解了证据")));

        CriticResult result = criticAgent.verify(reasoning, bundle);

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectReasons()).contains("假设 1 曲解了证据");
    }

    private EvidenceBundle bundleWith(Evidence... evidences) {
        AlertContext alert = new AlertContext(
                "a1", "svc", "prod", null, null, null, "fp", null);
        LogAnalysisResult logResult = new LogAnalysisResult(
                List.of(), "", "unknown", List.of(evidences), false, null);
        return new EvidenceBundle(alert, Optional.of(logResult), Optional.empty(), Optional.empty());
    }

    private ReasoningResult reasoning(RootCauseHypothesis... hypotheses) {
        return new ReasoningResult(List.of(hypotheses), "影响范围");
    }

    private RootCauseHypothesis hypothesis(ConfidenceLevel confidence, List<Evidence> supporting) {
        return new RootCauseHypothesis("根因摘要", confidence, supporting, List.of());
    }

    private Evidence evidence(String sourceRef) {
        return new Evidence("描述", EvidenceSourceType.LOG, sourceRef, "片段");
    }
}
