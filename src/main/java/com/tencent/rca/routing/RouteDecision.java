package com.tencent.rca.routing;

import java.util.Set;

/**
 * 告警路由决策结果 (详细设计文档 6.3).
 *
 * @param activatedAgents    本次需激活的证据收集 Agent 名称集合
 * @param llmSuggestedReview 是否走了 LLM 兜底分类, 建议人工复核路由结果
 * @param note               路由决策说明
 */
public record RouteDecision(
        Set<String> activatedAgents,
        boolean llmSuggestedReview,
        String note) {
}
