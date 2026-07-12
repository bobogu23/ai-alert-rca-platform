package com.tencent.rca.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.tencent.rca.common.enums.SuspectedCause;
import com.tencent.rca.config.RcaProperties;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.fault.RetryExecutor;
import com.tencent.rca.fault.TimeLimiter;
import com.tencent.rca.mcp.GalileoMcpClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * HypothesisVerifier 单元测试: 校验阈值判定的证实/证伪/不可判定. Galileo 全程 mock, 使用真实容错组件.
 */
@ExtendWith(MockitoExtension.class)
class HypothesisVerifierTest {

    @Mock
    private GalileoMcpClient galileoMcpClient;

    private HypothesisVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new HypothesisVerifier(
                galileoMcpClient, new RetryExecutor(), new TimeLimiter(), new RcaProperties());
    }

    @Test
    void shouldConfirmWhenRedisLatencyOverThreshold() {
        // redis 耗时阈值 200ms, 观测 500ms -> 证实
        when(galileoMcpClient.fetchMetricByName(any(), anyString()))
                .thenReturn(Map.of("value", 500.0));

        VerificationResult result = verifier.verify(alert(), SuspectedCause.REDIS);

        assertThat(result.conclusive()).isTrue();
        assertThat(result.confirmed()).isTrue();
        assertThat(result.evidence()).isPresent();
    }

    @Test
    void shouldDisproveWhenValueUnderThreshold() {
        when(galileoMcpClient.fetchMetricByName(any(), anyString()))
                .thenReturn(Map.of("value", 50.0));

        VerificationResult result = verifier.verify(alert(), SuspectedCause.REDIS);

        assertThat(result.conclusive()).isTrue();
        assertThat(result.confirmed()).isFalse();
    }

    @Test
    void shouldBeInconclusiveWhenMetricMissing() {
        when(galileoMcpClient.fetchMetricByName(any(), anyString())).thenReturn(Map.of());

        VerificationResult result = verifier.verify(alert(), SuspectedCause.GC);

        assertThat(result.conclusive()).isFalse();
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void shouldBeInconclusiveWhenQueryFails() {
        when(galileoMcpClient.fetchMetricByName(any(), anyString()))
                .thenThrow(new RuntimeException("galileo down"));

        VerificationResult result = verifier.verify(alert(), SuspectedCause.NETWORK);

        assertThat(result.conclusive()).isFalse();
        assertThat(result.confirmed()).isFalse();
    }

    @Test
    void shouldBeInconclusiveForUnknownCause() {
        VerificationResult result = verifier.verify(alert(), SuspectedCause.UNKNOWN);

        assertThat(result.conclusive()).isFalse();
    }

    private AlertContext alert() {
        return new AlertContext("a1", "svc", "prod", null, null, null, "fp", Map.of());
    }
}
