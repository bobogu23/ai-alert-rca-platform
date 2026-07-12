package com.tencent.rca.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tencent.rca.common.enums.EvidenceSourceType;
import com.tencent.rca.common.enums.SuspectedCause;
import com.tencent.rca.config.RcaProperties;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.Evidence;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * InvestigationService 单元测试: 校验决策树转向、证实收敛与超轮次收敛. HypothesisVerifier 全程 mock.
 */
@ExtendWith(MockitoExtension.class)
class InvestigationServiceTest {

    @Mock
    private HypothesisVerifier verifier;

    private InvestigationService service;

    @BeforeEach
    void setUp() {
        service = new InvestigationService(verifier, new RcaProperties());
    }

    @Test
    void shouldConfirmAtStartCauseAndStop() {
        when(verifier.verify(any(), eq(SuspectedCause.REDIS)))
                .thenReturn(confirmed(SuspectedCause.REDIS));

        InvestigationOutcome outcome = service.investigate(alert(), SuspectedCause.REDIS);

        assertThat(outcome.confirmedCause()).contains(SuspectedCause.REDIS);
        assertThat(outcome.verifications()).hasSize(1);
        assertThat(outcome.evidences()).hasSize(1);
        // 起点即证实, 不应再验证其他方向
        verify(verifier, times(1)).verify(any(), any());
    }

    @Test
    void shouldPivotWhenStartCauseDisproved() {
        // REDIS 证伪 -> 转向 GC 证实
        when(verifier.verify(any(), eq(SuspectedCause.REDIS)))
                .thenReturn(disproved(SuspectedCause.REDIS));
        when(verifier.verify(any(), eq(SuspectedCause.GC)))
                .thenReturn(confirmed(SuspectedCause.GC));

        InvestigationOutcome outcome = service.investigate(alert(), SuspectedCause.REDIS);

        assertThat(outcome.confirmedCause()).contains(SuspectedCause.GC);
        verify(verifier).verify(any(), eq(SuspectedCause.REDIS));
        verify(verifier).verify(any(), eq(SuspectedCause.GC));
    }

    @Test
    void shouldConvergeWithoutConfirmationWhenAllDisproved() {
        // 全部不可判定/证伪, 最多 3 轮后收敛, 无证实方向
        when(verifier.verify(any(), any())).thenAnswer(inv ->
                inconclusive(inv.getArgument(1)));

        InvestigationOutcome outcome = service.investigate(alert(), SuspectedCause.REDIS);

        assertThat(outcome.confirmedCause()).isEmpty();
        // maxRounds 默认 3, 至多验证 3 次
        verify(verifier, times(3)).verify(any(), any());
    }

    private VerificationResult confirmed(SuspectedCause cause) {
        return new VerificationResult(cause, true, true, "成立", Optional.of(evidence(cause)));
    }

    private VerificationResult disproved(SuspectedCause cause) {
        return new VerificationResult(cause, false, true, "不成立", Optional.of(evidence(cause)));
    }

    private VerificationResult inconclusive(SuspectedCause cause) {
        return new VerificationResult(cause, false, false, "不可判定", Optional.empty());
    }

    private Evidence evidence(SuspectedCause cause) {
        return new Evidence("验证 " + cause, EvidenceSourceType.METRIC, "metric@svc", "snippet");
    }

    private AlertContext alert() {
        return new AlertContext("a1", "svc", "prod", null, null, null, "fp", Map.of());
    }
}
