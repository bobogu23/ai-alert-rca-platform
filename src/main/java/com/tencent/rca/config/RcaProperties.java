package com.tencent.rca.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 归因系统核心配置, 对应 application.yml 中的 rca.* 节点.
 */
@Data
@ConfigurationProperties(prefix = "rca")
public class RcaProperties {

    /** 大模型分级路由配置. */
    private Llm llm = new Llm();
    /** 分层超时预算, 对应详细设计文档 7.2. */
    private Timeout timeout = new Timeout();
    /** 去重配置. */
    private Dedup dedup = new Dedup();
    /** Critic 校验循环配置. */
    private Critic critic = new Critic();
    /** 上下文 token 预算配置. */
    private ContextBudget contextBudget = new ContextBudget();
    /** 堆栈裁剪包名规则. */
    private StackPruning stackPruning = new StackPruning();
    /** 编排并发限流配置. */
    private Concurrency concurrency = new Concurrency();
    /** 迭代取证-证伪调查配置. */
    private Investigation investigation = new Investigation();

    /** 大模型分级路由. */
    @Data
    public static class Llm {
        /** 推理任务使用的强模型名. */
        private String reasoningModel = "gpt-4o";
        /** 分类/摘要任务使用的轻量模型名. */
        private String lightweightModel = "gpt-4o-mini";
        /** LLM 调用最大重试次数. */
        private int maxRetries = 2;
        /** 高风险结论的多数投票采样次数. */
        private int selfConsistencySamples = 3;
    }

    /** 分层超时预算 (毫秒). 层级需满足 mcp < llm < agent, overall 覆盖多轮 critic. */
    @Data
    public static class Timeout {
        /** 单次 MCP 工具调用超时. */
        private long mcpCallMs = 10000;
        /** 单个证据收集 Agent 超时. */
        private long agentMs = 70000;
        /** 单次 LLM 调用超时. 真实推理模型较慢, 放宽到 60s. */
        private long llmCallMs = 60000;
        /** Critic 校验循环整体超时. */
        private long criticLoopMs = 70000;
        /** 整体分析任务超时. */
        private long overallMs = 180000;
    }

    /** 去重配置. */
    @Data
    public static class Dedup {
        /** 同指纹冷却窗口秒数. */
        private long cooldownSeconds = 600;
    }

    /** Critic 校验配置. */
    @Data
    public static class Critic {
        /** 最大迭代轮次. */
        private int maxIterations = 2;
    }

    /** 上下文 token 预算. */
    @Data
    public static class ContextBudget {
        /** 根因推理 Agent token 预算. */
        private int reasoningAgentTokens = 12000;
        /** Critic Agent token 预算. */
        private int criticAgentTokens = 8000;
        /** 证据收集 Agent token 预算. */
        private int evidenceAgentTokens = 6000;
    }

    /** 堆栈裁剪包名规则. */
    @Data
    public static class StackPruning {
        /** 业务代码包名前缀白名单. */
        private List<String> businessPackages = new ArrayList<>();
        /** 框架代码包名前缀黑名单. */
        private List<String> frameworkPackages = new ArrayList<>();
    }

    /** 编排并发限流配置 (详细设计文档 7.8). */
    @Data
    public static class Concurrency {
        /** 同时进行的分析任务上限. */
        private int maxConcurrentAnalyses = 16;
        /** 等待队列容量, 超出则拒绝以背压保护下游. */
        private int queueCapacity = 128;
    }

    /** 迭代取证-证伪调查配置. */
    @Data
    public static class Investigation {
        /** 是否启用迭代取证闭环. */
        private boolean enabled = true;
        /** 最大调查轮次, 超限则用当前证据收敛出报告. */
        private int maxRounds = 3;
    }
}
