package com.tencent.rca.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.tencent.rca.common.enums.CaseStatus;
import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.common.exception.RcaException;
import com.tencent.rca.repository.AlertCaseRepository;
import com.tencent.rca.repository.entity.AlertCaseEntity;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CaseService 状态机单元测试: 校验合法/非法流转、幂等与结论写入. Repository 全程 mock.
 */
@ExtendWith(MockitoExtension.class)
class CaseServiceTest {

    @Mock
    private AlertCaseRepository alertCaseRepository;

    private CaseService caseService;

    @BeforeEach
    void setUp() {
        caseService = new CaseService(alertCaseRepository);
        // 部分用例(未找到/非法流转)不会走到 save, 用 lenient 避免严格模式误报多余 stub
        lenient().when(alertCaseRepository.save(any(AlertCaseEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void shouldMarkAnalyzingFromOpen() {
        AlertCaseEntity entity = entityWithStatus(CaseStatus.OPEN);
        when(alertCaseRepository.findById(1L)).thenReturn(Optional.of(entity));

        caseService.markAnalyzing(1L);

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.ANALYZING);
    }

    @Test
    void shouldCompleteAnalysisAndWriteConclusion() {
        AlertCaseEntity entity = entityWithStatus(CaseStatus.ANALYZING);
        when(alertCaseRepository.findById(1L)).thenReturn(Optional.of(entity));

        caseService.completeAnalysis(1L, "根因结论", ConfidenceLevel.HIGH, "url", "降级说明");

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.ANALYZED);
        assertThat(entity.getRootCauseSummary()).isEqualTo("根因结论");
        assertThat(entity.getConfidenceLevel()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(entity.getReportUrl()).isEqualTo("url");
        assertThat(entity.getDegradeNote()).isEqualTo("降级说明");
    }

    @Test
    void shouldRejectIllegalTransition() {
        AlertCaseEntity entity = entityWithStatus(CaseStatus.ANALYZED);
        when(alertCaseRepository.findById(1L)).thenReturn(Optional.of(entity));

        // ANALYZED 不允许回退到 ANALYZING
        assertThatThrownBy(() -> caseService.markAnalyzing(1L))
                .isInstanceOf(RcaException.class)
                .hasMessageContaining("非法状态流转");
    }

    @Test
    void shouldBeIdempotentWhenTargetEqualsCurrent() {
        AlertCaseEntity entity = entityWithStatus(CaseStatus.ANALYZED);
        when(alertCaseRepository.findById(1L)).thenReturn(Optional.of(entity));

        // 已 ANALYZED 再次置 ANALYZED 应幂等不抛异常
        caseService.transitionTo(1L, CaseStatus.ANALYZED);

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.ANALYZED);
    }

    @Test
    void shouldThrowWhenCaseNotFound() {
        when(alertCaseRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.markAnalyzing(9L))
                .isInstanceOf(RcaException.class)
                .hasMessageContaining("案卷不存在");
    }

    @Test
    void shouldIncrementMergedCountOnAppendOccurrence() {
        AlertCaseEntity entity = entityWithStatus(CaseStatus.ANALYZING);
        entity.setMergedAlertCount(2);
        when(alertCaseRepository.findById(1L)).thenReturn(Optional.of(entity));

        caseService.appendOccurrence(1L);

        assertThat(entity.getMergedAlertCount()).isEqualTo(3);
    }

    private AlertCaseEntity entityWithStatus(CaseStatus status) {
        AlertCaseEntity entity = new AlertCaseEntity();
        entity.setId(1L);
        entity.setServiceName("svc");
        entity.setStatus(status);
        entity.setMergedAlertCount(1);
        return entity;
    }
}
