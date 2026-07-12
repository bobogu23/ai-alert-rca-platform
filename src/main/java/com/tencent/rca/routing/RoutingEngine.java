package com.tencent.rca.routing;

import com.tencent.rca.common.enums.AlertType;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.llm.LlmClient;
import com.tencent.rca.llm.ModelTier;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 告警分类路由引擎 (详细设计文档 6.3).
 * 双通道: 规则引擎(确定性匹配)优先, LLM 轻量兜底次之. 规则命中即返回, 规则无法匹配才走 LLM;
 * 完全无法分类时回退默认策略(至少激活日志分析). 规则第一版本地配置化, 预留后续迁七彩石.
 */
@Slf4j
@Component
public class RoutingEngine {

    /** 日志分析 Agent 标识. */
    public static final String LOG_AGENT = "LogAnalysisAgent";
    /** Trace/Metric 分析 Agent 标识. */
    public static final String TRACE_AGENT = "TraceMetricAgent";
    /** 代码上下文 Agent 标识. */
    public static final String CODE_AGENT = "CodeContextAgent";

    private static final String SYSTEM_PROMPT = """
            你是告警分类助手. 请将给定告警归类到以下类型之一: LOG_THRESHOLD(异常日志阈值), LATENCY(接口耗时/超时),
            RESOURCE(JVM或容器资源异常), OTHER(其他). 输出 alertType 与简要 reason.""";

    private final LlmClient llmClient;

    public RoutingEngine(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 决定本次分析需激活的证据收集 Agent 集合.
     *
     * @param alert 告警上下文
     * @return 路由决策
     */
    public RouteDecision route(AlertContext alert) {
        AlertType alertType = alert.alertType();
        if (alertType != null && alertType != AlertType.OTHER) {
            return new RouteDecision(agentsFor(alertType), false, "规则命中: " + alertType);
        }
        return routeByLlm(alert);
    }

    private RouteDecision routeByLlm(AlertContext alert) {
        try {
            String userPrompt = "服务: " + alert.serviceName()
                    + "\n原始告警: " + String.valueOf(alert.rawAlertPayload());
            RouteLlmOutput output = llmClient.completeStructured(
                    ModelTier.LIGHTWEIGHT, SYSTEM_PROMPT, userPrompt, RouteLlmOutput.class);
            AlertType parsed = parseAlertType(output.alertType());
            String note = "LLM 兜底分类: " + parsed + " (" + orEmpty(output.reason()) + ")";
            return new RouteDecision(agentsFor(parsed), true, note);
        } catch (RuntimeException ex) {
            log.warn("路由 LLM 兜底失败, 回退默认策略: {}", ex.getMessage());
            return defaultDecision("LLM 兜底失败, 回退默认策略: " + ex.getMessage());
        }
    }

    private Set<String> agentsFor(AlertType alertType) {
        Set<String> agents = new LinkedHashSet<>();
        switch (alertType) {
            case LOG_THRESHOLD -> {
                agents.add(LOG_AGENT);
                agents.add(CODE_AGENT);
            }
            case LATENCY -> {
                agents.add(LOG_AGENT);
                agents.add(TRACE_AGENT);
                agents.add(CODE_AGENT);
            }
            case RESOURCE -> {
                agents.add(TRACE_AGENT);
                agents.add(CODE_AGENT);
            }
            default -> agents.add(LOG_AGENT);
        }
        return agents;
    }

    private RouteDecision defaultDecision(String note) {
        Set<String> agents = new LinkedHashSet<>();
        agents.add(LOG_AGENT);
        return new RouteDecision(agents, true, note);
    }

    private AlertType parseAlertType(String value) {
        if (!StringUtils.hasText(value)) {
            return AlertType.OTHER;
        }
        try {
            return AlertType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return AlertType.OTHER;
        }
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
