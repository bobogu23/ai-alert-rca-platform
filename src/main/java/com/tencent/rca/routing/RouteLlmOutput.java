package com.tencent.rca.routing;

/**
 * RoutingEngine 中 LLM 轻量兜底分类的输出契约 (详细设计文档 6.3).
 * LLM 仅在规则无法匹配时被调用, 将告警归类到已知告警类型.
 *
 * @param alertType 归类到的告警类型字符串 (LOG_THRESHOLD/LATENCY/RESOURCE/OTHER)
 * @param reason    分类依据
 */
public record RouteLlmOutput(
        String alertType,
        String reason) {
}
