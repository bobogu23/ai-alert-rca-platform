package com.tencent.rca.agent;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * ReportGeneratorAgent 真实 LLM 冒烟测试: 走真实 LLM 生成建议动作并渲染报告.
 * 默认跳过, 设置环境变量 RCA_LLM_IT=true 后运行. 断言宽松, 只校验报告主体字段完整且建议动作非空.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RCA_LLM_IT", matches = "true")
class ReportGeneratorAgentLlmIT {

    @Autowired
    private ReportGeneratorAgent reportGeneratorAgent;

    @Test
    void shouldGenerateReportWithRealLlmSuggestions() {
        Evidence e = new Evidence("下游超时证据", EvidenceSourceType.TRACE, "trace-1", "ReadTimeout 120 次");
        RootCauseHypothesis hypothesis = new RootCauseHypothesis(
                "下游依赖超时", ConfidenceLevel.MEDIUM, List.of(e), List.of());
        ReasoningResult reasoning = new ReasoningResult(List.of(hypothesis), "服务 svc 全量受影响");

        RcaReport report = reportGeneratorAgent.generate(1L, alert(), reasoning, bundle(e), null);

        // 报告主体字段由本地确定性组装, 建议动作由真实 LLM 生成; 只校验完整性与非空
        assertThat(report).isNotNull();
        assertThat(report.fallback()).isFalse();
        assertThat(report.conclusion()).isEqualTo("下游依赖超时");
        assertThat(report.suggestedActions()).isNotEmpty();
        assertThat(report.markdownBody()).contains("trace-1");
    }

    private EvidenceBundle bundle(Evidence e) {
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
