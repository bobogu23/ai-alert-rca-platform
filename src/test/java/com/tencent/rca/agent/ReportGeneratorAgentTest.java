package com.tencent.rca.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.tencent.rca.common.enums.AlertSource;
import com.tencent.rca.common.enums.AlertType;
import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.common.enums.EvidenceSourceType;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.Evidence;
import com.tencent.rca.domain.EvidenceBundle;
import com.tencent.rca.domain.LogAnalysisResult;
import com.tencent.rca.domain.RcaReport;
import com.tencent.rca.domain.ReasoningResult;
import com.tencent.rca.domain.RootCauseHypothesis;
import com.tencent.rca.domain.TimeWindow;
import com.tencent.rca.llm.LlmClient;
import com.tencent.rca.llm.ModelTier;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ReportGeneratorAgent 单元测试: 校验结构化字段组装与 LLM 建议动作失败降级. LlmClient mock.
 */
@ExtendWith(MockitoExtension.class)
class ReportGeneratorAgentTest {

    @Mock
    private LlmClient llmClient;

    private ReportGeneratorAgent agent;

    @BeforeEach
    void setUp() {
        agent = new ReportGeneratorAgent(llmClient);
    }

    @Test
    void shouldRenderStructuredFieldsFromReasoning() {
        when(llmClient.completeStructured(eq(ModelTier.REASONING), any(), any(), eq(SuggestedActionOutput.class)))
                .thenReturn(new SuggestedActionOutput(List.of("重启下游", "扩容连接池")));

        RcaReport report = agent.generate(1L, alert(), reasoning(ConfidenceLevel.HIGH), bundle(), null);

        assertThat(report.fallback()).isFalse();
        assertThat(report.conclusion()).isEqualTo("下游依赖超时");
        assertThat(report.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(report.suggestedActions()).contains("重启下游");
        assertThat(report.markdownBody()).contains("trace-1");
        assertThat(report.markdownBody()).contains("根因结论");
    }

    @Test
    void shouldFallbackToDefaultActionsWhenLlmFails() {
        when(llmClient.completeStructured(eq(ModelTier.REASONING), any(), any(), eq(SuggestedActionOutput.class)))
                .thenThrow(new RuntimeException("llm down"));

        RcaReport report = agent.generate(1L, alert(), reasoning(ConfidenceLevel.MEDIUM), bundle(), "部分降级");

        assertThat(report.suggestedActions()).isNotEmpty();
        assertThat(report.degradeNote()).isEqualTo("部分降级");
    }

    @Test
    void shouldAddPendingItemWhenLowConfidence() {
        when(llmClient.completeStructured(eq(ModelTier.REASONING), any(), any(), eq(SuggestedActionOutput.class)))
                .thenReturn(new SuggestedActionOutput(List.of("排查")));

        RcaReport report = agent.generate(1L, alert(), reasoning(ConfidenceLevel.LOW), bundle(), null);

        assertThat(report.pendingItems()).anyMatch(p -> p.contains("置信度较低"));
    }

    private ReasoningResult reasoning(ConfidenceLevel confidence) {
        Evidence e = new Evidence("下游超时证据", EvidenceSourceType.TRACE, "trace-1", "snippet");
        RootCauseHypothesis hypothesis = new RootCauseHypothesis(
                "下游依赖超时", confidence, List.of(e), List.of());
        return new ReasoningResult(List.of(hypothesis), "服务 svc 全量受影响");
    }

    private EvidenceBundle bundle() {
        Evidence e = new Evidence("下游超时证据", EvidenceSourceType.TRACE, "trace-1", "snippet");
        LogAnalysisResult logResult = new LogAnalysisResult(
                List.of(), "A -> B -> C", "downstream", List.of(e), false, null);
        return new EvidenceBundle(alert(), Optional.of(logResult), Optional.empty(), Optional.empty());
    }

    private AlertContext alert() {
        TimeWindow window = new TimeWindow(
                OffsetDateTime.parse("2026-07-10T10:00:00+08:00"),
                OffsetDateTime.parse("2026-07-10T10:05:00+08:00"));
        return new AlertContext("a1", "svc", "prod", AlertSource.CLS, AlertType.LATENCY, window, "fp", null);
    }
}
