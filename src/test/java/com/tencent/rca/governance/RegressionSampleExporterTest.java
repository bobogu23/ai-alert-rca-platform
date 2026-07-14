package com.tencent.rca.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.common.enums.FeedbackErrorCategory;
import com.tencent.rca.common.enums.FeedbackType;
import com.tencent.rca.common.enums.SuspectedCause;
import com.tencent.rca.repository.AlertFeedbackRepository;
import com.tencent.rca.repository.entity.AlertFeedbackEntity;
import com.tencent.rca.security.SensitiveDataMasker;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RegressionSampleExporter 单元测试: 校验高可信 badcase 过滤、脱敏、导出与已导出标记.
 */
@ExtendWith(MockitoExtension.class)
class RegressionSampleExporterTest {

    @Mock
    private AlertFeedbackRepository feedbackRepository;

    private RegressionSampleExporter exporter;

    @BeforeEach
    void setUp() {
        // 可信度打分器与脱敏器用真实实现, 保证行为真实
        exporter = new RegressionSampleExporter(
                feedbackRepository, new FeedbackCredibilityScorer(), new SensitiveDataMasker());
    }

    @Test
    void shouldExportHighCredibilityBadcaseWithMaskingAndMarkAdopted() {
        AlertFeedbackEntity senior = badcase(1L, "fp-1", 3, "root cause: token=abc123secret");
        AlertFeedbackEntity juniorLowCred = badcase(2L, "fp-2", 1, "junior guess");
        when(feedbackRepository.findByFeedbackTypeAndFeedbackAtAfterAndAdoptedToExperience(
                any(), any(), any())).thenReturn(List.of(senior, juniorLowCred));

        List<RegressionSample> samples = exporter.exportRejectedBadcases(7);

        // 仅资深高可信被导出
        assertThat(samples).hasSize(1);
        RegressionSample sample = samples.get(0);
        assertThat(sample.sampleId()).isEqualTo("regression-1");
        assertThat(sample.actualDirection()).isEqualTo("GC");
        assertThat(sample.errorCategory()).isEqualTo("WRONG_DIRECTION");
        // 敏感 token 被脱敏
        assertThat(sample.aiRootCauseSummary()).doesNotContain("abc123secret");
        // 被导出的反馈标记 adopted
        assertThat(senior.getAdoptedToExperience()).isTrue();
        assertThat(juniorLowCred.getAdoptedToExperience()).isNull();
        verify(feedbackRepository).saveAll(any());
    }

    @Test
    void shouldNotSaveWhenNoHighCredibilityCandidate() {
        AlertFeedbackEntity junior = badcase(3L, "fp-3", 1, "low cred");
        when(feedbackRepository.findByFeedbackTypeAndFeedbackAtAfterAndAdoptedToExperience(
                any(), any(), any())).thenReturn(List.of(junior));

        List<RegressionSample> samples = exporter.exportRejectedBadcases(7);

        assertThat(samples).isEmpty();
        verify(feedbackRepository, never()).saveAll(any());
    }

    private AlertFeedbackEntity badcase(Long id, String fp, int level, String aiSummary) {
        AlertFeedbackEntity e = new AlertFeedbackEntity();
        e.setId(id);
        e.setFingerprint(fp);
        e.setServiceName("svc");
        e.setFeedbackType(FeedbackType.REJECTED);
        e.setFeedbackerLevel(level);
        e.setAiRootCauseSummary(aiSummary);
        e.setAiConfidence(ConfidenceLevel.HIGH);
        e.setActualRootCauseDirection(SuspectedCause.GC);
        e.setActualRootCauseSummary("实为 GC 停顿");
        e.setCorrectEvidenceHint("看 jvm.gc.pause");
        e.setErrorCategory(FeedbackErrorCategory.WRONG_DIRECTION);
        return e;
    }
}
