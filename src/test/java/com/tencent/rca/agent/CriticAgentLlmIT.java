package com.tencent.rca.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.common.enums.EvidenceSourceType;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.CriticResult;
import com.tencent.rca.domain.Evidence;
import com.tencent.rca.domain.EvidenceBundle;
import com.tencent.rca.domain.LogAnalysisResult;
import com.tencent.rca.domain.ReasoningResult;
import com.tencent.rca.domain.RootCauseHypothesis;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * CriticAgent 真实 LLM 冒烟测试: 硬校验通过后走真实 LLM 语义校验.
 * 默认跳过, 设置环境变量 RCA_LLM_IT=true 后运行. 断言宽松, 只校验流程可跑通且返回非空, 不校验语义结论.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RCA_LLM_IT", matches = "true")
class CriticAgentLlmIT {

    @Autowired
    private CriticAgent criticAgent;

    @Test
    void shouldRunSemanticCheckWithRealLlm() {
        Evidence e1 = new Evidence("下游服务超时次数显著上升", EvidenceSourceType.TRACE, "trace-1", "downstream timeout 120 次");
        Evidence e2 = new Evidence("错误日志集中在调用下游处", EvidenceSourceType.LOG, "log-1", "ReadTimeout at callDownstream");
        EvidenceBundle bundle = bundleWith(e1, e2);
        ReasoningResult reasoning = new ReasoningResult(
                List.of(new RootCauseHypothesis("下游依赖超时导致本服务错误", ConfidenceLevel.MEDIUM, List.of(e1, e2), List.of())),
                "服务 svc");

        CriticResult result = criticAgent.verify(reasoning, bundle);

        // 只验证真实 LLM 语义校验链路可跑通, 返回结构非空; 不对 passed 具体取值做强断言
        assertThat(result).isNotNull();
        assertThat(result.rejectReasons()).isNotNull();
    }

    private EvidenceBundle bundleWith(Evidence... evidences) {
        AlertContext alert = new AlertContext("a1", "svc", "prod", null, null, null, "fp", null);
        LogAnalysisResult logResult = new LogAnalysisResult(
                List.of(), "", "unknown", List.of(evidences), false, null);
        return new EvidenceBundle(alert, Optional.of(logResult), Optional.empty(), Optional.empty());
    }
}
