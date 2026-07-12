package com.tencent.rca.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencent.rca.common.enums.AlertType;
import com.tencent.rca.domain.AlertContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * RoutingEngine 真实 LLM 冒烟测试: 对 OTHER 类型走真实 LLM 轻量兜底分类.
 * 默认跳过, 设置环境变量 RCA_LLM_IT=true 后运行. 断言宽松, 只校验兜底链路可跑通且至少激活一个 Agent.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RCA_LLM_IT", matches = "true")
class RoutingEngineLlmIT {

    @Autowired
    private RoutingEngine routingEngine;

    @Test
    void shouldFallbackToRealLlmForOtherType() {
        AlertContext alert = new AlertContext(
                "a1", "svc", "prod", null, AlertType.OTHER, null, "fp",
                Map.of("summary", "接口 P99 耗时从 200ms 上升到 3s"));

        RouteDecision decision = routingEngine.route(alert);

        // 走了 LLM 兜底分支, 结果应至少激活一个 Agent, 且标记建议复核
        assertThat(decision).isNotNull();
        assertThat(decision.activatedAgents()).isNotEmpty();
        assertThat(decision.llmSuggestedReview()).isTrue();
    }
}
