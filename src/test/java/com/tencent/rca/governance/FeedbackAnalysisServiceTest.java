package com.tencent.rca.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.tencent.rca.common.enums.FeedbackErrorCategory;
import com.tencent.rca.common.enums.FeedbackType;
import com.tencent.rca.common.enums.SuspectedCause;
import com.tencent.rca.repository.AlertFeedbackRepository;
import com.tencent.rca.repository.entity.AlertFeedbackEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * FeedbackAnalysisService 单元测试: 校验飞轮指标计算 (采纳率) 与多维聚合. Repository 全程 mock.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackAnalysisServiceTest {

    @Mock
    private AlertFeedbackRepository feedbackRepository;

    private FeedbackAnalysisService analysisService;

    @BeforeEach
    void setUp() {
        analysisService = new FeedbackAnalysisService(feedbackRepository);
    }

    @Test
    void shouldComputeAdoptionRate() {
        when(feedbackRepository.countByFeedbackAtAfter(any())).thenReturn(10L);
        when(feedbackRepository.countByFeedbackTypeAndFeedbackAtAfter(eq(FeedbackType.CONFIRMED), any()))
                .thenReturn(7L);
        when(feedbackRepository.countByFeedbackTypeAndFeedbackAtAfter(eq(FeedbackType.PARTIAL), any()))
                .thenReturn(1L);
        when(feedbackRepository.countByFeedbackTypeAndFeedbackAtAfter(eq(FeedbackType.REJECTED), any()))
                .thenReturn(1L);
        when(feedbackRepository.countByFeedbackTypeAndFeedbackAtAfter(eq(FeedbackType.FALSE_POSITIVE), any()))
                .thenReturn(1L);

        FlywheelMetrics metrics = analysisService.computeFlywheelMetrics(7);

        assertThat(metrics.totalFeedback()).isEqualTo(10L);
        assertThat(metrics.confirmedCount()).isEqualTo(7L);
        assertThat(metrics.adoptionRate()).isEqualTo(0.7);
    }

    @Test
    void shouldReturnZeroAdoptionRateWhenNoFeedback() {
        when(feedbackRepository.countByFeedbackAtAfter(any())).thenReturn(0L);
        when(feedbackRepository.countByFeedbackTypeAndFeedbackAtAfter(any(), any())).thenReturn(0L);

        FlywheelMetrics metrics = analysisService.computeFlywheelMetrics(7);

        assertThat(metrics.adoptionRate()).isZero();
    }

    @Test
    void shouldAggregateByMultipleDimensions() {
        when(feedbackRepository.findByFeedbackAtAfter(any())).thenReturn(List.of(
                reject("fp-1", "svcA", SuspectedCause.GC, FeedbackErrorCategory.WRONG_DIRECTION),
                reject("fp-1", "svcA", SuspectedCause.GC, FeedbackErrorCategory.WRONG_DIRECTION),
                partial("fp-2", "svcB", SuspectedCause.NETWORK, FeedbackErrorCategory.INCOMPLETE),
                confirmed("fp-3", "svcC")));

        FeedbackAggregation aggregation = analysisService.aggregate(7);

        // 错误类型: WRONG_DIRECTION x2, INCOMPLETE x1, CONFIRMED 无错误类型归入 UNSPECIFIED x1
        assertThat(aggregation.byErrorCategory()).containsEntry("WRONG_DIRECTION", 2L);
        assertThat(aggregation.byErrorCategory()).containsEntry("INCOMPLETE", 1L);
        assertThat(aggregation.byErrorCategory()).containsEntry("UNSPECIFIED", 1L);
        // 真实方向
        assertThat(aggregation.byActualDirection()).containsEntry("GC", 2L);
        assertThat(aggregation.byActualDirection()).containsEntry("NETWORK", 1L);
        // 服务维度只算负反馈: svcA x2, svcB x1, svcC(confirmed) 不计
        assertThat(aggregation.byService()).containsEntry("svcA", 2L);
        assertThat(aggregation.byService()).containsEntry("svcB", 1L);
        assertThat(aggregation.byService()).doesNotContainKey("svcC");
        // 高频 badcase 指纹: 仅 REJECTED, fp-1 出现 2 次
        assertThat(aggregation.topBadcaseFingerprints()).containsEntry("fp-1", 2L);
    }

    private AlertFeedbackEntity reject(String fp, String svc, SuspectedCause dir, FeedbackErrorCategory cat) {
        AlertFeedbackEntity e = base(fp, svc, FeedbackType.REJECTED);
        e.setActualRootCauseDirection(dir);
        e.setErrorCategory(cat);
        return e;
    }

    private AlertFeedbackEntity partial(String fp, String svc, SuspectedCause dir, FeedbackErrorCategory cat) {
        AlertFeedbackEntity e = base(fp, svc, FeedbackType.PARTIAL);
        e.setActualRootCauseDirection(dir);
        e.setErrorCategory(cat);
        return e;
    }

    private AlertFeedbackEntity confirmed(String fp, String svc) {
        return base(fp, svc, FeedbackType.CONFIRMED);
    }

    private AlertFeedbackEntity base(String fp, String svc, FeedbackType type) {
        AlertFeedbackEntity e = new AlertFeedbackEntity();
        e.setFingerprint(fp);
        e.setServiceName(svc);
        e.setFeedbackType(type);
        e.setFeedbackAt(LocalDateTime.now());
        return e;
    }
}
