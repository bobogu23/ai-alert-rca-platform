package com.tencent.rca.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tencent.rca.common.enums.AlertType;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.llm.LlmClient;
import com.tencent.rca.llm.ModelTier;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RoutingEngine 单元测试: 规则命中优先、LLM 兜底与默认回退. LlmClient 全程 mock.
 */
@ExtendWith(MockitoExtension.class)
class RoutingEngineTest {

    @Mock
    private LlmClient llmClient;

    private RoutingEngine routingEngine;

    @BeforeEach
    void setUp() {
        routingEngine = new RoutingEngine(llmClient);
    }

    @Test
    void shouldRouteLogThresholdByRuleWithoutLlm() {
        RouteDecision decision = routingEngine.route(alert(AlertType.LOG_THRESHOLD));

        assertThat(decision.activatedAgents())
                .containsExactlyInAnyOrder(RoutingEngine.LOG_AGENT, RoutingEngine.CODE_AGENT);
        assertThat(decision.llmSuggestedReview()).isFalse();
        verifyNoInteractions(llmClient);
    }

    @Test
    void shouldRouteLatencyWithTraceAgent() {
        RouteDecision decision = routingEngine.route(alert(AlertType.LATENCY));

        assertThat(decision.activatedAgents()).contains(RoutingEngine.TRACE_AGENT);
    }

    @Test
    void shouldFallbackToLlmForOtherType() {
        when(llmClient.completeStructured(eq(ModelTier.LIGHTWEIGHT), any(), any(), eq(RouteLlmOutput.class)))
                .thenReturn(new RouteLlmOutput("LATENCY", "耗时升高"));

        RouteDecision decision = routingEngine.route(alert(AlertType.OTHER));

        assertThat(decision.llmSuggestedReview()).isTrue();
        assertThat(decision.activatedAgents()).contains(RoutingEngine.TRACE_AGENT);
    }

    @Test
    void shouldFallbackToDefaultWhenLlmFails() {
        when(llmClient.completeStructured(eq(ModelTier.LIGHTWEIGHT), any(), any(), eq(RouteLlmOutput.class)))
                .thenThrow(new RuntimeException("llm down"));

        RouteDecision decision = routingEngine.route(alert(AlertType.OTHER));

        assertThat(decision.activatedAgents()).containsExactly(RoutingEngine.LOG_AGENT);
        assertThat(decision.llmSuggestedReview()).isTrue();
    }

    private AlertContext alert(AlertType type) {
        return new AlertContext("a1", "svc", "prod", null, type, null, "fp", Map.of());
    }
}
