package com.tencent.rca.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.repository.SuppressionRuleRepository;
import com.tencent.rca.repository.entity.SuppressionRuleEntity;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 告警抑制引擎 (详细设计文档 6.2).
 * 依据 suppression_rule 表配置, 对处于维护窗/静默时段/依赖屏蔽列表内的告警进行抑制; 被抑制告警不触发分析.
 * 规则解析健壮: match_condition 解析失败的单条规则被跳过, 不影响其他规则.
 */
@Slf4j
@Service
public class SuppressionEngine {

    private final SuppressionRuleRepository suppressionRuleRepository;
    private final ObjectMapper objectMapper;

    public SuppressionEngine(SuppressionRuleRepository suppressionRuleRepository, ObjectMapper objectMapper) {
        this.suppressionRuleRepository = suppressionRuleRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 评估告警是否应被抑制.
     *
     * @param alert 告警上下文
     * @return 抑制判定结果
     */
    public SuppressionResult evaluate(AlertContext alert) {
        LocalDateTime now = LocalDateTime.now();
        for (SuppressionRuleEntity rule : suppressionRuleRepository.findByEnabledTrue()) {
            if (!isEffective(rule, now)) {
                continue;
            }
            if (matches(rule, alert)) {
                return new SuppressionResult(true, rule.getRuleType().name());
            }
        }
        return new SuppressionResult(false, null);
    }

    private boolean isEffective(SuppressionRuleEntity rule, LocalDateTime now) {
        if (rule.getEffectiveStart() != null && now.isBefore(rule.getEffectiveStart())) {
            return false;
        }
        return rule.getEffectiveEnd() == null || !now.isAfter(rule.getEffectiveEnd());
    }

    private boolean matches(SuppressionRuleEntity rule, AlertContext alert) {
        Map<String, Object> condition = parseCondition(rule.getMatchCondition());
        if (condition.isEmpty()) {
            return false;
        }
        // 所有给定的匹配键都需与告警属性一致, 才判定命中
        for (Map.Entry<String, Object> entry : condition.entrySet()) {
            if (!attributeMatches(entry.getKey(), entry.getValue(), alert)) {
                return false;
            }
        }
        return true;
    }

    private boolean attributeMatches(String key, Object expected, AlertContext alert) {
        String expectedValue = String.valueOf(expected);
        return switch (key) {
            case "service", "serviceName" -> expectedValue.equals(alert.serviceName());
            case "env" -> expectedValue.equals(alert.env());
            case "alertType" -> alert.alertType() != null && expectedValue.equalsIgnoreCase(alert.alertType().name());
            case "alertSource" -> alert.alertSource() != null
                    && expectedValue.equalsIgnoreCase(alert.alertSource().name());
            default -> matchesPayload(alert, key, expectedValue);
        };
    }

    private boolean matchesPayload(AlertContext alert, String key, String expectedValue) {
        if (alert.rawAlertPayload() == null) {
            return false;
        }
        Object actual = alert.rawAlertPayload().get(key);
        return actual != null && expectedValue.equals(String.valueOf(actual));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCondition(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException ex) {
            log.warn("抑制规则 match_condition 解析失败, 跳过该规则: {}", ex.getMessage());
            return Map.of();
        }
    }
}
