package com.tencent.rca.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tencent.rca.api.dto.FeedbackRequest;
import com.tencent.rca.common.enums.CaseStatus;
import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.common.enums.FeedbackErrorCategory;
import com.tencent.rca.common.enums.FeedbackType;
import com.tencent.rca.common.enums.SuspectedCause;
import com.tencent.rca.observability.RcaMetrics;
import com.tencent.rca.repository.AlertFeedbackRepository;
import com.tencent.rca.repository.entity.AlertCaseEntity;
import com.tencent.rca.repository.entity.AlertFeedbackEntity;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * FeedbackService 单元测试: 校验反馈落库、诊断快照记录、状态流转映射与指标上报. 依赖全程 mock.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock
    private AlertFeedbackRepository feedbackRepository;
    @Mock
    private CaseService caseService;
    @Mock
    private RcaMetrics metrics;

    private FeedbackService feedbackService;

    @BeforeEach
    void setUp() {
        feedbackService = new FeedbackService(feedbackRepository, caseService, metrics);
    }

    @Test
    void shouldPersistSnapshotAndTransitionToResolvedOnConfirmed() {
        AlertCaseEntity caseEntity = caseEntity();
        when(caseService.getById(1L)).thenReturn(Optional.of(caseEntity));
        FeedbackRequest request = new FeedbackRequest(
                FeedbackType.CONFIRMED, null, null, null, null, null, "alice", 3);

        feedbackService.record(1L, request);

        ArgumentCaptor<AlertFeedbackEntity> captor = ArgumentCaptor.forClass(AlertFeedbackEntity.class);
        verify(feedbackRepository).save(captor.capture());
        AlertFeedbackEntity saved = captor.getValue();
        // 诊断快照来自案卷
        assertThat(saved.getAiRootCauseSummary()).isEqualTo("疑似 Redis 超时");
        assertThat(saved.getAiConfidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(saved.getFingerprint()).isEqualTo("fp-1");
        assertThat(saved.getServiceName()).isEqualTo("svc");
        assertThat(saved.getFeedbackerLevel()).isEqualTo(3);
        assertThat(saved.getAdoptedToExperience()).isFalse();
        // 状态流转 + 指标
        verify(caseService).transitionTo(1L, CaseStatus.RESOLVED);
        verify(metrics).recordFeedback("CONFIRMED");
    }

    @Test
    void shouldRecordCorrectionAndTransitionToAcknowledgedOnRejected() {
        when(caseService.getById(2L)).thenReturn(Optional.of(caseEntity()));
        FeedbackRequest request = new FeedbackRequest(
                FeedbackType.REJECTED, SuspectedCause.GC, "实为 GC 停顿", "看 jvm.gc.pause",
                FeedbackErrorCategory.WRONG_DIRECTION, "建议看 GC", "bob", 2);

        feedbackService.record(2L, request);

        ArgumentCaptor<AlertFeedbackEntity> captor = ArgumentCaptor.forClass(AlertFeedbackEntity.class);
        verify(feedbackRepository).save(captor.capture());
        AlertFeedbackEntity saved = captor.getValue();
        assertThat(saved.getActualRootCauseDirection()).isEqualTo(SuspectedCause.GC);
        assertThat(saved.getErrorCategory()).isEqualTo(FeedbackErrorCategory.WRONG_DIRECTION);
        assertThat(saved.getCorrectEvidenceHint()).isEqualTo("看 jvm.gc.pause");
        verify(caseService).transitionTo(2L, CaseStatus.ACKNOWLEDGED);
        verify(metrics).recordFeedback("REJECTED");
    }

    @Test
    void shouldTransitionToFalsePositiveOnFalsePositiveFeedback() {
        when(caseService.getById(3L)).thenReturn(Optional.of(caseEntity()));
        FeedbackRequest request = new FeedbackRequest(
                FeedbackType.FALSE_POSITIVE, null, null, null, null, null, null, null);

        feedbackService.record(3L, request);

        verify(caseService).transitionTo(3L, CaseStatus.FALSE_POSITIVE);
        // 未指定级别时默认 1
        ArgumentCaptor<AlertFeedbackEntity> captor = ArgumentCaptor.forClass(AlertFeedbackEntity.class);
        verify(feedbackRepository).save(captor.capture());
        assertThat(captor.getValue().getFeedbackerLevel()).isEqualTo(1);
    }

    @Test
    void shouldNotFailFeedbackWhenTransitionThrows() {
        when(caseService.getById(4L)).thenReturn(Optional.of(caseEntity()));
        // 状态流转抛异常 (如非法流转), 反馈仍应落库不抛出
        org.mockito.Mockito.doThrow(new RuntimeException("非法状态流转"))
                .when(caseService).transitionTo(eq(4L), any());
        FeedbackRequest request = new FeedbackRequest(
                FeedbackType.PARTIAL, null, null, null, null, null, null, null);

        feedbackService.record(4L, request);

        verify(feedbackRepository).save(any(AlertFeedbackEntity.class));
        verify(metrics).recordFeedback("PARTIAL");
    }

    @Test
    void shouldThrowWhenCaseNotFound() {
        when(caseService.getById(9L)).thenReturn(Optional.empty());
        FeedbackRequest request = new FeedbackRequest(
                FeedbackType.CONFIRMED, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> feedbackService.record(9L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("案卷不存在");
        verify(feedbackRepository, never()).save(any());
        verify(metrics, never()).recordFeedback(anyString());
    }

    private AlertCaseEntity caseEntity() {
        AlertCaseEntity entity = new AlertCaseEntity();
        entity.setId(1L);
        entity.setFingerprint("fp-1");
        entity.setServiceName("svc");
        entity.setRootCauseSummary("疑似 Redis 超时");
        entity.setConfidenceLevel(ConfidenceLevel.HIGH);
        entity.setStatus(CaseStatus.ANALYZED);
        return entity;
    }
}
