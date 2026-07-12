package com.tencent.rca.llm;

/**
 * 大模型档位, 用于按 Agent 角色分级选择 LiteLLM 中的不同模型 (详细设计文档 4.5).
 */
public enum ModelTier {
    /** 强模型, 用于根因推理、Critic 校验等高价值任务. */
    REASONING,
    /** 轻量模型, 用于分类、摘要等低风险任务, 控制成本. */
    LIGHTWEIGHT
}
