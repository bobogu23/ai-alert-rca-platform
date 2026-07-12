package com.tencent.rca.report;

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
import com.tencent.rca.domain.TimeWindow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * FallbackReportBuilder 单元测试: 校验兜底报告字段与空证据渲染.
 */
class FallbackReportBuilderTest {

    private final FallbackReportBuilder builder = new FallbackReportBuilder();

    @Test
    void shouldBuildFallbackWithEmptyBundle() {
        EvidenceBundle empty = new EvidenceBundle(alert(), Optional.empty(), Optional.empty(), Optional.empty());

        RcaReport report = builder.buildFallback(1L, alert(), empty, "整体分析超时");

        assertThat(report.fallback()).isTrue();
        assertThat(report.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(report.degradeNote()).isEqualTo("整体分析超时");
        assertThat(report.conclusion()).contains("请人工排查");
        assertThat(report.markdownBody()).contains("未采集到任何证据");
    }

    @Test
    void shouldIncludeEvidenceSnapshotWhenPresent() {
        Evidence e = new Evidence("异常出现 10 次", EvidenceSourceType.LOG, "trace-1", "snippet");
        LogAnalysisResult logResult = new LogAnalysisResult(List.of(), "", "unknown", List.of(e), true, "部分降级");
        EvidenceBundle bundle = new EvidenceBundle(alert(), Optional.of(logResult), Optional.empty(), Optional.empty());

        RcaReport report = builder.buildFallback(2L, alert(), bundle, "LLM 不可用");

        assertThat(report.markdownBody()).contains("trace-1");
        assertThat(report.markdownBody()).contains("异常出现 10 次");
    }

    private AlertContext alert() {
        TimeWindow window = new TimeWindow(
                OffsetDateTime.parse("2026-07-10T10:00:00+08:00"),
                OffsetDateTime.parse("2026-07-10T10:05:00+08:00"));
        return new AlertContext("a1", "svc", "prod", AlertSource.CLS, AlertType.LOG_THRESHOLD, window, "fp", null);
    }
}
