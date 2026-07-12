package com.tencent.rca.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.rca.common.enums.SuppressionRuleType;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.repository.SuppressionRuleRepository;
import com.tencent.rca.repository.entity.SuppressionRuleEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SuppressionEngine 单元测试: 命中/未命中/失效窗/解析异常降级. 仓储 mock, 使用真实 ObjectMapper.
 */
@ExtendWith(MockitoExtension.class)
class SuppressionEngineTest {

    @Mock
    private SuppressionRuleRepository suppressionRuleRepository;

    private SuppressionEngine suppressionEngine;

    @BeforeEach
    void setUp() {
        suppressionEngine = new SuppressionEngine(suppressionRuleRepository, new ObjectMapper());
    }

    @Test
    void shouldSuppressWhenServiceMatches() {
        when(suppressionRuleRepository.findByEnabledTrue())
                .thenReturn(List.of(rule("{\"service\":\"svc\"}", null, null)));

        SuppressionResult result = suppressionEngine.evaluate(alert("svc"));

        assertThat(result.suppressed()).isTrue();
        assertThat(result.ruleType()).isEqualTo(SuppressionRuleType.MAINTENANCE.name());
    }

    @Test
    void shouldNotSuppressWhenServiceMismatch() {
        when(suppressionRuleRepository.findByEnabledTrue())
                .thenReturn(List.of(rule("{\"service\":\"other\"}", null, null)));

        SuppressionResult result = suppressionEngine.evaluate(alert("svc"));

        assertThat(result.suppressed()).isFalse();
    }

    @Test
    void shouldNotSuppressWhenOutsideEffectiveWindow() {
        LocalDateTime past = LocalDateTime.now().minusDays(2);
        when(suppressionRuleRepository.findByEnabledTrue())
                .thenReturn(List.of(rule("{\"service\":\"svc\"}", past.minusDays(1), past)));

        SuppressionResult result = suppressionEngine.evaluate(alert("svc"));

        assertThat(result.suppressed()).isFalse();
    }

    @Test
    void shouldSkipRuleWithInvalidJson() {
        when(suppressionRuleRepository.findByEnabledTrue())
                .thenReturn(List.of(rule("not-a-json", null, null)));

        SuppressionResult result = suppressionEngine.evaluate(alert("svc"));

        assertThat(result.suppressed()).isFalse();
    }

    private SuppressionRuleEntity rule(String condition, LocalDateTime start, LocalDateTime end) {
        SuppressionRuleEntity entity = new SuppressionRuleEntity();
        entity.setRuleType(SuppressionRuleType.MAINTENANCE);
        entity.setMatchCondition(condition);
        entity.setEffectiveStart(start);
        entity.setEffectiveEnd(end);
        entity.setEnabled(true);
        return entity;
    }

    private AlertContext alert(String service) {
        return new AlertContext("a1", service, "prod", null, null, null, "fp", Map.of());
    }
}
