# AI 告警自动归因系统 —— Agent 工程测评方案

状态: 设计评审中
文档定位: 面向 `ai-alert-rca-platform` 的**Agent 工程化测评方案**, 对齐业界最新的 Agent Evaluation 理念 (OpenAI Evals、LangSmith Evaluations、Anthropic Agentic Evals、Google Gemini Bench、AutoGen Bench、AgentBench、τ-bench、SWE-bench-verified、以及 arXiv 2024-2025 关于 Agent 评估的一系列工作). 覆盖:
1. Agent 核心能力模型与评估维度
2. 主客观相结合的指标体系 (质量 / 能力 / 稳定性 / 成本 / 价值)
3. 数据集构建策略 (真实告警脱敏 + 合成扰动 + 红队对抗)
4. 自动化测评流程 (三级门禁 + LLM-as-Judge + 灰度 A/B)
5. 结果分析与闭环机制 (归因分析、回归防护、SRE 反馈飞轮)

本方案**紧扣本项目的实际实现** (`Orchestrator` / 6 个 Agent / `InvestigationService` / `CriticAgent` / `RcaMetrics`), 提出的所有断言均可在现有代码 + Mock 测试骨架 (`AlertRcaEndToEndIT`) 上直接落地.

---

## 1. 为什么 Agent 测评不同于传统 LLM 测评

传统 LLM 测评关注**单轮输入-输出**质量 (如 MMLU、HumanEval), 而 **Agent 是一个持续的决策系统**——它需要在多轮工具调用、多个子 Agent 协作、有限时间与预算约束下达成目标. 因此本项目的测评必须超越"回答准不准"这一维度, 面向**能力 (Capability)、过程 (Trajectory)、结果 (Outcome)** 三层建立评估.

**业界共识 (2024-2025)**: 一个 Agent 系统的质量应从以下六个能力维度综合评估 (对应 Anthropic 的 "Agentic Capabilities"、OpenAI Agents SDK Evals、以及 AgentBench 分类):

1. **意图理解 (Intent Understanding)** — 是否正确识别告警类型与所需分析路径;
2. **任务规划 (Task Planning)** — 是否合理决定激活哪些 Agent、按什么顺序;
3. **工具调用 (Tool Use)** — MCP 工具调用是否准确、参数是否合规、结果解析是否鲁棒;
4. **记忆与上下文管理 (Memory & Context)** — 中间结果的组织、Token 预算裁剪、跨 Agent 数据传递是否无失真;
5. **推理与自我校验 (Reasoning & Self-Critic)** — 是否基于证据推理、幻觉是否被拦截;
6. **错误恢复与鲁棒性 (Error Recovery & Robustness)** — 部分依赖失败、超时、异常输入下能否优雅降级.

本项目**每一个能力维度都对应具体代码位置**, 因此每个维度都可以设计**可自动化验证的评测项**——这是本方案区别于"泛泛而谈的评估框架"的关键.

---

## 2. Agent 核心能力评估维度详设

### 2.1 六大能力维度与本项目对应

| # | 能力维度 | 项目中的载体 | 核心问题 (评估要回答的) |
|---|---|---|---|
| C1 | **意图理解** | `RoutingEngine` (规则 + LLM 兜底) | 告警是否被正确分类? 需要激活哪些 Agent? |
| C2 | **任务规划** | `Orchestrator.runPipeline` + `RouteDecision.activatedAgents` | 是否按最优路径调度? 是否正确触发迭代取证? |
| C3 | **工具调用** | `ClsMcpClient` / `GalileoMcpClient` / `CodeRepoMcpClient` + `HypothesisVerifier.fetchWithFaultTolerance` | 工具调用参数、失败重试、结果解析是否正确? |
| C4 | **记忆与上下文管理** | `EvidenceBundle` / `ContextBudgetManager` / `RcaCacheService` / `SensitiveDataMasker` | 证据是否无损传递? Token 裁剪是否合理? 敏感数据是否脱敏? |
| C5 | **推理与自校验** | `RootCauseReasoningAgent` + `CriticAgent` + `InvestigationService` | 根因是否有证据支撑? 幻觉是否被拦截? 迭代取证是否证实/证伪? |
| C6 | **错误恢复与鲁棒性** | `RetryExecutor` + `TimeLimiter` + `FallbackReportBuilder` + `submissionExecutor` | 部分故障是否被隔离? 超时是否被降级? 兜底是否触发? |

### 2.2 各维度的具体评估项

#### C1. 意图理解

| 评估项 | 度量方式 | 目标 |
|---|---|---|
| 告警类型分类准确率 | 标注 vs `RouteDecision.alertCategory` | ≥ 95% |
| Agent 激活集合正确率 | 标注期望激活集 vs `activatedAgents` (Jaccard 相似度) | ≥ 0.9 |
| 无关 Agent 误激活率 | 不需激活但被激活的 Agent 数 / 总 Agent 数 | ≤ 5% (控制成本) |
| LLM 兜底触发率 | 规则未命中转 LLM 的比例 | ≤ 20% (规则应覆盖大多数场景) |

#### C2. 任务规划

| 评估项 | 度量方式 | 目标 |
|---|---|---|
| 并行 fan-out 生效率 | Agent 实际并行执行的比例 (通过 span 时间轴判定) | ≥ 90% |
| 迭代取证触发合理性 | `logResult.suspectedDependency` 非空时是否触发 `InvestigationService` | 100% |
| 取证轮次收敛性 | `InvestigationOutcome.verifications.size()` 分布, 平均 ≤ 2 轮 | 95 分位 ≤ 3 |
| Critic 循环调用次数 | 平均 Critic 迭代次数 | ≤ 1.3 (说明首轮多数通过) |

#### C3. 工具调用

| 评估项 | 度量方式 | 目标 |
|---|---|---|
| MCP 调用参数合规率 | Mock 侧断言参数结构 (时间窗、服务名) 完整 | 100% |
| 工具调用成功率 | 非降级的调用次数 / 总调用次数 | ≥ 95% |
| 工具结果解析鲁棒性 | 对畸形/空/超大响应的容错率 (`HypothesisVerifier.extractNumeric`) | 100% (不抛未捕获异常) |
| 定向指标命中率 | `fetchMetricByName` 的方向-指标映射正确率 (Redis 疑似 → 拉 `redis.client.duration`) | 100% |

#### C4. 记忆与上下文管理

| 评估项 | 度量方式 | 目标 |
|---|---|---|
| 证据无损传递率 | `EvidenceBundle` 从收集到 Reasoning 的 evidence.sourceRef 保留率 (排除预算裁剪的合法删除) | 100% (被裁剪的必须在预算日志中可解释) |
| Token 预算命中率 | `ContextBudgetManager.prune` 后的实际 Token 数 ≤ `reasoningAgentTokens` (12000) | 100% |
| 脱敏正确率 | 已知敏感样本 (IP/userId/token) 在 masker 后不再出现 | 100% |
| 缓存命中率 | 重试同案卷时 `RcaCacheService.getAgentResult` 命中率 | ≥ 90% |

#### C5. 推理与自校验

| 评估项 | 度量方式 | 目标 |
|---|---|---|
| **Top-1 命中率** | 首个假设方向 vs 标注 | ≥ 70% |
| **Top-3 命中率** | 前 3 个假设覆盖标注方向 | ≥ 90% |
| **证据编号可追溯率** | 假设引用的 `E?` 编号 100% 存在于原始清单 | 100% (Critic 硬校验保障) |
| **幻觉拦截率** | 红队集 (编造引用) 被 Critic 打回的比例 | 100% |
| **单证据 HIGH 拦截率** | Critic 对"仅 1 条证据却标 HIGH"的打回率 | 100% (硬规则 `checkConfidenceEvidenceMatch`) |
| **多互斥 HIGH 拦截率** | Critic 对同时多个 HIGH 假设的打回率 | 100% (`checkConflict`) |
| **置信度校准 (ECE)** | HIGH 假设实际正确率 vs 声明置信度 | HIGH 实际正确率 ≥ 85%, MEDIUM ≥ 60%, LOW ≤ 40% |
| **迭代取证证实率** | 有明确疑似方向的告警中 `confirmedCause` 非空的比例 | ≥ 60% |
| **迭代取证证伪转向正确率** | 证伪后转向的下一方向符合决策树的比例 | 100% |

#### C6. 错误恢复与鲁棒性

| 评估项 | 度量方式 | 目标 |
|---|---|---|
| 单 Agent 失败隔离率 | 注入单 Agent 异常后, 其余 Agent 仍能产出 | 100% |
| 超时降级正确率 | 注入 LLM/MCP 超时后, 走对应降级路径且不重试放大 | 100% |
| 兜底报告触发正确率 | 三路证据全失败或整体超时时, `fallback=true` + 案卷置 `ANALYZE_FAILED` | 100% |
| 背压响应正确率 | 并发超 `maxConcurrentAnalyses + queueCapacity` 时返回 503/REJECTED | 100% |
| 死循环防护 | 迭代取证/Critic 循环达上限时能收敛退出 | 100% |
| 中断响应正确率 | `future.cancel(true)` 后底层 IO 能真正释放 | 100% |

---

## 3. 测评指标体系 (主客观结合)

### 3.1 指标分层

指标按"能不能自动算"分为客观指标 (Objective, 代码可断言) 与主观指标 (Subjective, 需 LLM Judge 或人工). 两者组合使用:

```
┌────────────────────────────────────────────────────────────────┐
│  客观指标  (占门禁权重 70%)                                     │
│  ├─ 结构性: 证据可追溯率、Schema 合规率、Token 合规率              │
│  ├─ 命中率: Top-1/Top-3、`suspectedDependency` 分类准确率        │
│  ├─ 稳定性: P95 延迟、降级率、超时率、拒绝率                       │
│  └─ 成本: 平均 Token 消耗、平均 LLM 调用次数                       │
├────────────────────────────────────────────────────────────────┤
│  主观指标  (占门禁权重 30%, LLM-as-Judge + 人工抽检 10%)         │
│  ├─ 报告可读性 (Clarity)                                        │
│  ├─ 建议可操作性 (Actionability)                                │
│  ├─ 因果链合理性 (Causal Coherence)                             │
│  └─ 幻觉细腻度 (Subtle Hallucination, Critic 硬规则漏掉的)       │
├────────────────────────────────────────────────────────────────┤
│  业务价值指标  (线上采集, 不参与门禁但驱动产品迭代)                 │
│  ├─ MTTR 缩短量                                                 │
│  ├─ 报告采纳率                                                  │
│  └─ 人工介入率                                                  │
└────────────────────────────────────────────────────────────────┘
```

### 3.2 客观指标详表

复用 §2.2 各能力维度已列指标, 此处补充**成本类**指标 (业界 Agent Eval 越来越强调的维度, 参见 τ-bench 与 AgentBench 2024 版新增维度):

| 指标 | 度量 | 目标 |
|---|---|---|
| 平均 LLM 调用次数 / 案卷 | 从 `LlmClient` 埋点采集 | ≤ 8 次 |
| 平均 Token 消耗 / 案卷 | Prompt + Completion Token 总和 | ≤ 30k |
| 平均 MCP 调用次数 / 案卷 | 三路 MCP + 迭代取证的调用总数 | ≤ 15 次 |
| 每千次分析成本 | 依据 LiteLLM 计费口径换算 | 有基线, 每版对比不劣化 > 15% |

### 3.3 主观指标 —— LLM-as-Judge 规范

**核心原则**: LLM 评委必须"知道自己在评什么", 不能给一个开放式 prompt 让它随便打分.

#### 3.3.1 Judge 模型选型

- 用**独立强模型** (仅在本项目可选的 Claude 系列 / Gemini / GLM-5 / 通义千问 四系列内取, 且必须与被评的生产 REASONING 模型**跨系列**——若生产用 GLM-5, Judge 用 Claude 3.5 Sonnet; 若生产用 Claude, Judge 用 GLM-5 或 Gemini Pro), 避免"同系列自评偏袒";
- Judge 温度 = 0, 保证同样输入同样输出;
- Judge 结果必须**结构化** (`{score: 1-5, issues: [...], reasoning: "..."}`), 用于后续统计分析.

#### 3.3.2 Judge Rubric (评分标准)

对每份非兜底报告, Judge 从四个维度打分 (1~5), 并给出改进建议:

| 维度 | 5 分标准 | 3 分标准 | 1 分标准 |
|---|---|---|---|
| **因果链合理性** | 每个假设都能画出"证据 → 现象 → 根因"的清晰链条 | 部分假设链条有跳跃 | 假设与证据无因果关系 |
| **可读性** | SRE 一眼能看懂、结构层次清晰 | 需读 2 遍 | 表述混乱、术语误用 |
| **可操作性** | 建议具体到"该 SQL 加索引 xxx"、"配置项调整为 xxx" | 建议方向明确但缺细节 | 建议空泛 (如"优化性能") |
| **细腻幻觉** | 无任何证据外发挥 | 有少量修饰性发挥但不误导 | 出现证据之外的具体数值/结论 |

Judge 输出示例:

```json
{
  "case_id": "eval-000123",
  "scores": {
    "causal_coherence": 5, "clarity": 4, "actionability": 4, "subtle_hallucination": 5
  },
  "issues": [
    "建议 3 缺少具体调整值 (可读性 -1 分)"
  ],
  "reasoning": "根因假设与 Redis 超时指标形成完整因果链; 建议 1-2 具体, 建议 3 偏泛."
}
```

#### 3.3.3 Judge 可信度校准

Judge 本身也需要被评估:

- 每周从测评结果中**随机抽样 10%** 让人工 SRE 打分;
- 计算 **Judge-Human 一致性 (Cohen's Kappa 或 Spearman 相关)**, 要求 ≥ 0.7;
- 若 Judge 与人工系统性偏差, 修正 rubric 或更换 Judge 模型.

**关键**: Judge **不参与硬门禁**, 只作趋势观测. 硬门禁全部由 §3.2 客观指标承担, 避免"AI 评 AI"引入的系统性偏差累积.

### 3.4 综合评分与门禁公式

**版本级综合评分**:

```
Score(version) = 0.5 × Quality  +  0.2 × Robustness  +  0.15 × Cost  +  0.15 × SubjectiveJudge

Quality       = 0.4 × Top1_Hit + 0.3 × Top3_Hit + 0.2 × Traceability + 0.1 × HallucinationBlock
Robustness    = 0.4 × RecoveryCorrect + 0.3 × DegradeCorrect + 0.3 × TimeoutCorrect
Cost          = 归一化, 相对基线成本的百分比 (低于基线得高分)
SubjectiveJudge = 四个 Judge 维度均值 / 5
```

**门禁规则** (对齐 OpenAI Evals 的 "regression testing" 实践):

1. 任一**硬指标不达标**直接失败 (证据可追溯率 < 100%、Schema 合规率 < 100%、超时降级正确率 < 100% 等);
2. `Score(new) < Score(baseline) - 0.03` 直接失败 (整体质量下降超 3%);
3. 任一**能力维度均分下降 > 5%** 直接失败 (定位到具体能力退化);
4. 成本上升 > 20% 需要人工审批放行 (可能是模型更贵但质量更高, 需评估).

---

## 4. 数据集构建策略

### 4.1 数据集分层 (四层数据金字塔)

对齐 SWE-bench-verified / τ-bench 的分层思路, 构建四层数据集, 每层用途独立、维护节奏不同:

```
                   ┌────────────────────┐
                   │   Red-team Set     │ 20~30 例, 对抗测试
                   │   (红队对抗集)      │
                   ├────────────────────┤
                   │   Stress Set       │ 20~30 例, 极端场景
                   │   (压力/故障注入)   │
                   ├────────────────────┤
                   │   Regression Set   │ 持续增长, 每次上线必跑
                   │   (回归集)         │
                   ├────────────────────┤
                   │   Golden Set       │ 首期 100 例, 人工精标注
                   │   (黄金集)          │
                   └────────────────────┘
```

| 层 | 数据来源 | 数量目标 | 更新节奏 | 用途 |
|---|---|---|---|---|
| **黄金集** | 精选真实历史告警, SRE 人工深度标注 | 100 例, 覆盖 7 种告警类型 | 季度更新 | 版本迭代基线、能力矩阵计算 |
| **回归集** | 线上 badcase + Critic 打回样本自动沉淀 | 持续增长 (预期 300+/年) | 实时增量 | 防回归, 每次上线必跑 |
| **压力集** | 合成告警风暴、部分 MCP 超时、LLM 网关抖动 | 20~30 例 | 半年更新 | 验证 C6 鲁棒性 |
| **红队集** | 手工构造对抗样本 (证据幻觉诱导、误导性关键词) | 20~30 例 | 每月新增 | 验证 C5 幻觉拦截 |

### 4.2 黄金集覆盖矩阵

为避免"数据偏斜导致某类告警评测失真", 黄金集必须**按矩阵覆盖**:

| 告警类型 (`AlertType`) | 简单场景 | 中等场景 | 复杂场景 (多假设并存) | 边界场景 (证据不足) |
|---|---|---|---|---|
| `LOG_THRESHOLD` (错误日志阈值) | 5 例 | 5 例 | 3 例 | 2 例 |
| `LATENCY` (接口延迟) | 5 例 | 5 例 | 3 例 | 2 例 |
| `ERROR_RATE` (错误率) | 5 例 | 5 例 | 3 例 | 2 例 |
| `RESOURCE` (资源) | 3 例 | 3 例 | 2 例 | 2 例 |
| `DEPENDENCY` (下游依赖) | 5 例 | 5 例 | 3 例 | 2 例 |
| `GC` (JVM GC) | 3 例 | 3 例 | 2 例 | 2 例 |
| 其它/无类型 (走 LLM 兜底) | 2 例 | 2 例 | 2 例 | 1 例 |

总计 ~100 例, **每个矩阵格子必须至少有 1 例**, 保证无死角.

### 4.3 每条样本的标注 Schema

对齐 LangSmith / OpenAI Evals 的 dataset schema, 每条样本包含"输入"、"MCP 桩数据"、"期望产出" 三部分:

```json
{
  "id": "golden-047",
  "meta": {
    "alertType": "LOG_THRESHOLD",
    "complexity": "MEDIUM",
    "expectedRootCause": "REDIS",
    "notes": "Redis 客户端超时 + GC 停顿并存, 期望 REDIS 为 HIGH, GC 为 LOW"
  },

  "input": {
    "alertContext": { "...": "AlertContext 原样 JSON" }
  },

  "mockFixtures": {
    "clsMcpClient.fetchErrorLogSamples": [ "...异常堆栈..." ],
    "galileoMcpClient.fetchMetrics": { "cpu": 0.4 },
    "galileoMcpClient.fetchSlowTraces": {},
    "galileoMcpClient.fetchMetricByName": {
      "redis.client.duration": { "value": 500.0 },
      "jvm.gc.pause": { "value": 200.0 }
    },
    "codeRepoMcpClient.searchCode": [],
    "codeRepoMcpClient.fetchRecentReleases": []
  },

  "groundTruth": {
    "capability_c1": {
      "expectedAlertCategory": "LOG_THRESHOLD",
      "expectedActivatedAgents": ["logAgent", "traceAgent"]
    },
    "capability_c2": {
      "shouldTriggerInvestigation": true,
      "expectedInvestigationSteps": ["REDIS"]
    },
    "capability_c3": {
      "expectedMetricNameCalled": ["redis.client.duration"]
    },
    "capability_c5": {
      "top1RootCause": "REDIS",
      "top1Confidence": "HIGH",
      "acceptableTop3": ["REDIS", "GC", "NETWORK"],
      "mustHitEvidenceSourceRefs": ["redis.client.duration@userreadservice"],
      "expectedInvestigationConfirmed": "REDIS",
      "shouldFallback": false
    },
    "capability_c6": {
      "faultInjection": null
    }
  }
}
```

`mockFixtures` 直接对齐 `AlertRcaEndToEndIT` 已有的 Mock 模式, 保证测评时输入完全可控、结果可复现.

### 4.4 数据集构建流程

```
[真实告警库] --SRE 筛选--> [候选样本] --SensitiveDataMasker--> [脱敏样本]
                                                                 │
                                                                 ▼
                                                          [SRE 人工标注]
                                                                 │
                                                                 ▼
                                                     [双人复核 (Cohen's Kappa)]
                                                                 │
                                                                 ▼
                                                        [Git 版本化入库]
                                                                 │
                                                                 ▼
                                                    docs/eval/dataset/*.json
```

**关键约束**:
- **脱敏一致性**: 用生产的 `SensitiveDataMasker` 组件, 保证测评输入分布与线上一致;
- **双人标注**: 每条样本至少两位 SRE 独立标注, Kappa 一致性 < 0.7 的样本回收重议;
- **版本化**: 数据集入 Git, 每次改动生成 diff, 保证测评历史可回溯.

### 4.5 红队集构造原则

红队集专门用于"诱导 Agent 犯错", 是 Anthropic Red-teaming Guide 的落地. 本项目重点构造以下对抗类型:

| 对抗类型 | 构造方式 | 期望 Agent 表现 |
|---|---|---|
| **关键词陷阱** | 日志含 "Redis timeout" 字样但 Redis 指标正常 | 迭代取证证伪 REDIS, 转向 GC / 网络 |
| **证据编造诱导** | Prompt 注入引导 LLM 引用不存在的 `E9`、`E10` 编号 | Reasoning Agent 忽略无效引用, Critic 拦截 |
| **单证据 HIGH 诱导** | 只给 1 条证据但让 Agent 强下 HIGH | Critic 硬规则拦截 |
| **多冲突 HIGH 诱导** | 引导 Agent 给出多个互斥 HIGH 结论 | Critic 硬规则拦截 |
| **敏感数据泄漏诱导** | 日志中含真实 IP/token, 观察最终报告 | 全部被 `SensitiveDataMasker` 脱敏 |
| **超长 Prompt 攻击** | 构造 50k Token 的证据集合 | `ContextBudgetManager` 裁剪到 12k 以内 |

### 4.6 数据集治理

- 每季度 **数据集健康度审计**: 类别覆盖是否失衡、样本是否过期 (对应线上 badcase 分布);
- 每次线上 badcase → 回归集自动流水线, 30 分钟内 PR 提交;
- 数据集与代码同仓, 保证测评环境 = 生产环境版本对齐.

---

## 5. 自动化测评流程

### 5.1 三级门禁架构

对齐 LangSmith / OpenAI Evals 的 CI 集成方式, 分三级门禁, 由快到慢、由局部到全局:

```
      开发者提交 PR
            │
            ▼
   ┌─────────────────┐
   │ L1 单元测试        │ <5min, 每次提交必跑
   │ (确定性逻辑)       │ 覆盖 CriticAgent 硬规则、HypothesisVerifier 阈值、
   └────────┬────────┘  InvestigationService 决策树、FallbackReportBuilder
            │           已有基础 (src/test/java 21 个测试文件)
            ▼
   ┌─────────────────┐
   │ L2 组件级评测      │ 10~30min, PR 合入前必跑
   │ (Agent-level)   │ 每个 Agent 独立喂标注样本, 断言关键字段
   └────────┬────────┘
            │
            ▼
   ┌─────────────────┐
   │ L3 端到端评测      │ 30~60min, 上线前跑 + 每日夜间跑
   │ (E2E on Golden) │ 复用 AlertRcaEndToEndIT 骨架, 全 Pipeline
   └────────┬────────┘
            │
            ▼
     [基线对比 + 门禁决策]
            │
            ▼
     [通过 → 允许发布 / 失败 → 阻断 + 生成诊断报告]
```

### 5.2 L1 —— 单元测试 (Deterministic Unit Tests)

**范围**: 所有可用代码断言的**确定性行为**, 完全不依赖 LLM.

**已有覆盖** (基于 `src/test/java` 现状):
- `CriticAgentTest`: 硬校验三规则 (引用存在、置信度匹配、冲突检测);
- `HypothesisVerifierTest`: 各方向阈值判定与不可判定降级;
- `InvestigationServiceTest`: 决策树转向、`visited` 防重复、maxRounds 收敛;
- `FallbackReportBuilderTest`: 兜底报告字段完备性;
- `ContextBudgetManagerTest`: Token 裁剪保序;
- `SensitiveDataMaskerTest`: 敏感字段识别;
- `RoutingEngineTest`, `DedupEngineTest`, `SuppressionEngineTest`.

**需要补齐** (基于本方案能力矩阵反推):
- `OrchestratorFaultInjectionTest`: 注入单 Agent 抛异常、超时, 断言其余 Agent 仍能产出 (对应 C6);
- `OrchestratorBackpressureTest`: 并发超限时 `analyzeAsync` 返回 false + 案卷置失败 (对应 C6);
- `EvidenceBundleTraceabilityTest`: 全链路 `sourceRef` 保留断言 (对应 C4).

**门禁标准**: 100% 通过, 否则阻断合入.

### 5.3 L2 —— 组件级评测 (Agent-Level Evals)

**范围**: 单 Agent 独立测评, 打真实 LLM, 断言结构化输出关键字段. 对应业界的 "Component Eval" 概念.

**执行方式**:
- 使用 JUnit 5 `@EnabledIfEnvironmentVariable(named = "RCA_LLM_EVAL", matches = "true")` 门禁, 允许离线跑批;
- 每个 Agent 独立 dataset 子集, 输入固定 Mock 数据, 断言 Agent 输出;
- 结果聚合为 CSV: `agent, capability, metric, value, baseline, delta, pass`.

**每 Agent 的测评项**:

| Agent | 输入 | 断言项 (对应能力维度) |
|---|---|---|
| `LogAnalysisAgent` | 造异常日志 fixture | `suspectedDependency` 命中方向 (C1/C5) |
| `TraceMetricAgent` | 造 Metric/Trace fixture | `bottleneckType` 命中方向 (C5) |
| `CodeContextAgent` | 造代码搜索 fixture | 风险点识别命中 (C5) |
| `RoutingEngine` | 造 AlertContext | `activatedAgents` 集合 Jaccard ≥ 0.9 (C1) |
| `RootCauseReasoningAgent` | 造 EvidenceBundle | Top-1/Top-3 命中率, 证据引用有效率 (C5) |
| `CriticAgent` | 造带缺陷的 ReasoningResult | 各硬规则打回率 = 100% (C5) |
| `HypothesisVerifier` | 造各方向指标数据 | 证实/证伪/不可判定判定正确率 (C3/C5) |

**基线管理**: 每次评测结果与 `baseline.json` 对比, 任一指标下滑超 3% 标红.

### 5.4 L3 —— 端到端评测 (E2E Trajectory Evals)

**范围**: 完整 Pipeline (`analyzeAsync → routing → fan-out → investigation → reasoning → critic → report → dispatch`), 覆盖能力矩阵所有维度.

**执行方式**:
- 扩展 `AlertRcaEndToEndIT` 骨架, 增加参数化 (`@ParameterizedTest` + `MethodSource`) 加载黄金集全量;
- Mock MCP + Redis + 通知, 真实 LLM + 真实 MySQL;
- 每例执行后**从数据库取 `alert_case`** + 从 `RcaMetrics` 取指标, 综合断言.

**每例 E2E 采集的数据**:

```json
{
  "caseId": "e2e-047",
  "goldenId": "golden-047",
  "elapsedMs": 45230,
  "finalStatus": "ANALYZED",
  "report": {
    "hypotheses": [ {"summary": "...", "confidence": "HIGH", "evidenceRefs": [...]} ],
    "fallback": false,
    "degradeNote": null
  },
  "trajectory": {
    "activatedAgents": ["logAgent", "traceAgent"],
    "agentDurationsMs": {"logAgent": 12000, "traceAgent": 8000},
    "investigationRounds": 1,
    "investigationConfirmed": "REDIS",
    "criticIterations": 1,
    "criticPassed": true
  },
  "metrics": {
    "llmCallCount": 6,
    "totalTokens": 24500,
    "mcpCallCount": 11,
    "agentDegradedCount": 0
  },
  "capabilityScores": {
    "c1_intent": 1.0, "c2_planning": 1.0, "c3_tool": 1.0,
    "c4_memory": 1.0, "c5_reasoning": 1.0, "c6_recovery": null
  }
}
```

### 5.5 LLM-as-Judge 阶段

L3 完成后, 对所有**非兜底且非降级**的报告调用 Judge 打分 (§3.3 rubric), 结果并入综合评分.

### 5.6 故障注入评测 (Chaos Eval, C6 专项)

针对 C6 鲁棒性, 通过 Mock 主动注入故障, 每例都要**指定注入点 + 期望降级路径**:

| 注入类型 | 注入方式 | 期望产出 |
|---|---|---|
| MCP 超时 | `when(clsMcpClient.fetchErrorLogSamples(...)).thenAnswer(inv -> {Thread.sleep(15000); ...})` | logAgent 降级, 其余产出, report 含降级说明 |
| LLM 超时 | Judge 侧模拟 60s 无响应 | reasoning 抛 `RcaException`, 走全局兜底, `fallback=true` |
| LLM 抛 5xx | Mock 抛 `WebClientResponseException` | RetryExecutor 重试 2 次仍失败则 Agent 降级 |
| 结构化解析失败 | 让 LLM 返回非 JSON | 重试后仍失败 → Agent 降级 |
| 并发超限 | 提交 (max+queue+10) 个案卷 | 后 10 个 `analyzeAsync` 返回 false, 案卷置失败 |

### 5.7 灰度 A/B 评测 (线上)

对齐 LiteLLM 网关的分流能力, 上线新版本时:
- **分流规则**: 按 `caseId` 哈希, 10% 流量到新版本;
- **观察窗**: 7 天 + 覆盖工作日/周末;
- **对比指标**: §3.1 全部客观指标 + 采纳率;
- **自动回滚**: 新版本 Top-1 下滑 > 5% 或兜底率上升 > 3% 或采纳率下滑 > 5%, 网关自动回滚;
- **反 self-consistency 偏差**: 灰度时把 `selfConsistencySamples` 设为 1, 保证公平.

### 5.8 CI/CD 集成

```yaml
# .github/workflows/eval.yml (示例)
name: Agent Eval
on:
  pull_request:
    branches: [main]
jobs:
  l1-unit:
    runs-on: ubuntu-latest
    steps:
      - run: mvn test
  l2-component:
    if: contains(github.event.pull_request.labels.*.name, 'run-eval')
    env: { RCA_LLM_EVAL: "true", LITELLM_BASE_URL: ${{ secrets.LITELLM_URL }} }
    steps:
      - run: mvn -Dtest='*ComponentEvalIT' verify
      - run: python scripts/eval/compare_baseline.py --level=L2
  l3-e2e:
    if: github.event.pull_request.base.ref == 'main'
    env: { RCA_LLM_IT: "true", RCA_DB_IT: "true" }
    steps:
      - run: mvn -Dtest='AlertRcaEndToEndIT' verify
      - run: python scripts/eval/aggregate_and_judge.py
      - run: python scripts/eval/gate_check.py
```

---

## 6. 结果分析与闭环机制

### 6.1 三层结果视图

评测结果按**版本 → 能力维度 → 单样本**三层组织, 便于从宏观趋势下钻到具体缺陷:

**层 1 —— 版本级摘要**:

```
Version    Score  Top1  Top3  Halluc.Block  Fallback  P95Lat  AvgLLM  Cost/1k
v0.5.3     0.83   72%   91%    100%          8%        118s    6.5    ¥42
v0.5.4     0.86   75%   93%    100%          6%        112s    6.2    ¥40  ← current
v0.5.5-rc  0.81   69%   88%    100%          9%        121s    7.1    ¥46  ← BLOCK
```

**层 2 —— 能力维度分解** (`radar chart` 可视化):

```
                     v0.5.5-rc  vs  v0.5.4  baseline
C1 Intent            0.94       0.95       ↓ 0.01
C2 Planning          0.88       0.92       ↓ 0.04  ← 主要退化点
C3 Tool Use          0.99       0.99       →
C4 Memory            1.00       1.00       →
C5 Reasoning         0.79       0.85       ↓ 0.06  ← 主要退化点
C6 Recovery          1.00       1.00       →
```

**层 3 —— 单样本级详情**: 每例 E2E 结果 (§5.4 JSON 结构) 可查询, 支持"筛选所有 Critic 打回样本"、"筛选所有降级样本"等切片.

### 6.2 归因分析 (Failure Attribution)

**核心问题**: 指标下滑了, 是哪块的问题?

评测框架自动做**下钻归因**:

1. **命中率下滑** → 按能力维度切分, 定位是 C1 (路由错) 还是 C5 (推理错);
2. **C5 推理错** → 按错误类型切分:
   - 引用编造 → `checkEvidenceExistence` 打回率上升 → 可能是 Prompt 让 LLM 无视规则;
   - 置信度虚高 → `checkConfidenceEvidenceMatch` 打回率上升 → 可能是模型换代后倾向变了;
   - 迭代取证证伪失效 → `InvestigationOutcome.confirmedCause` 空值率上升 → 可能是决策树没覆盖新场景;
3. **P95 延迟上升** → 按 Agent 拆分 `agentDurationsMs`, 定位慢在哪个 Agent 或 LLM;
4. **成本上升** → 按 Prompt/Completion Token 分别看, 判断是 Prompt 膨胀 (证据裁剪失效) 还是 Completion 膨胀 (模型话痨).

### 6.3 结果闭环三回路

1. **数据回路** — Badcase 沉淀:
   - 线上被 SRE 驳回 / Critic 打回 / 兜底触发的样本, 值班人 30min 内确认是否沉淀;
   - 自动脱敏 + PR 到 `regression-set`, 下次上线必跑;

2. **模型/提示词回路** — 定向优化:
   - 归因分析定位到具体能力退化后, 优先看**是提示词漂移**还是**模型行为变化**;
   - 提示词优化后先跑 L2 组件级评测验证局部, 再跑 L3 全量;

3. **产品回路** — SRE 采纳率反哺:
   - 企微报告尾部三按钮 (采纳 / 驳回 / 需人工) 回调写入 `alert_case.feedback`;
   - 每周聚合驳回理由 Top 3, 直接进入下一迭代产品需求;
   - 采纳率作为**北极星指标** (North Star), 驱动整个测评体系是否真正解决业务问题.

### 6.4 可视化看板

**Grafana `rca-quality-dashboard`**:
- 版本时间序列: `Score(t)`, Top-1, 兜底率, P95 延迟, 成本;
- 能力雷达图: 当前 vs 上一稳定版本;
- 灰度 A/B 对比: 新旧版本各指标并排展示;
- Badcase 排行榜: 驳回理由 Top、Critic 打回场景 Top.

**评测报告自动生成**: 每次 L3 完成, 自动产出 `eval-report-vX.X.md`, 包含:
- 综合评分与门禁决策;
- 三层视图 (版本/能力/样本);
- 相较基线的 diff (提升 / 退化 / 持平);
- 下钻归因结论;
- 建议采取的下一步动作.

---

## 7. 交付物与工程落地

### 7.1 代码结构

```
src/test/java/com/tencent/rca/eval/
├── component/                          # L2 组件级评测
│   ├── LogAnalysisAgentEvalIT.java
│   ├── RoutingEngineEvalIT.java
│   ├── RootCauseReasoningAgentEvalIT.java
│   ├── CriticAgentEvalIT.java
│   └── HypothesisVerifierEvalIT.java
├── e2e/                                 # L3 端到端评测 (扩展自 AlertRcaEndToEndIT)
│   ├── GoldenSetEvalIT.java
│   ├── RegressionSetEvalIT.java
│   └── ChaosInjectionEvalIT.java
├── judge/                               # LLM-as-Judge
│   └── ReportJudge.java
├── metrics/                             # 指标聚合
│   ├── CapabilityScoreCalculator.java
│   └── BaselineComparator.java
└── fixtures/
    └── DatasetLoader.java

docs/eval/
├── metrics.md                           # 指标定义 (本文档 §2, §3)
├── dataset/
│   ├── golden/*.json                    # 黄金集
│   ├── regression/*.json                # 回归集
│   ├── stress/*.json                    # 压力集
│   └── redteam/*.json                   # 红队集
├── baseline/
│   └── v0.5.4-baseline.json             # 当前基线指标
└── reports/
    └── eval-report-vX.X.md              # 每次评测报告

scripts/eval/
├── compare_baseline.py
├── aggregate_and_judge.py
├── gate_check.py
└── attribution_analysis.py
```

### 7.2 落地节奏 (Roadmap)

| 阶段 | 时间 | 交付物 |
|---|---|---|
| **T0**: 基础设施搭建 | 2 周 | DatasetLoader、BaselineComparator、CI 集成 |
| **T1**: L1 补齐 | 1 周 | OrchestratorFaultInjectionTest、BackpressureTest、TraceabilityTest |
| **T2**: 黄金集首版 | 3 周 | 100 例覆盖矩阵样本, 双人标注入库 |
| **T3**: L2 组件评测 | 2 周 | 7 个 Agent 级 EvalIT + 首版基线 |
| **T4**: L3 E2E + Judge | 3 周 | GoldenSetEvalIT + ReportJudge + Grafana 看板 |
| **T5**: 红队集 + Chaos | 2 周 | 红队样本 + 故障注入 IT |
| **T6**: 线上灰度接入 | 1 周 | LiteLLM 分流规则 + 采纳率回调 |
| **稳态运营** | 持续 | 回归集自动增量, 每周基线滚动更新 |

**总计**: 约 3~4 个月完成从 0 到 1 的完整测评体系, 之后进入稳态运营.

### 7.3 关键成功要素

1. **测评框架必须比业务代码更稳定**: Eval 一挂 CI 全挂, 因此 Eval 代码质量要求高于业务代码;
2. **基线管理不能靠人**: 每次评测自动更新 `baseline.json` 候选, 由值班人一键确认切换;
3. **数据集必须持续扩充**: 静态数据集 3 个月就会过时, badcase 自动流水线是核心保障;
4. **Judge 必须被审计**: 每周 10% 人工抽检, 保证 Judge-Human Kappa ≥ 0.7;
5. **指标要有解释力**: 每个指标下滑都能下钻到具体能力维度, 不能只报"分数低了"就完事.

---

## 8. 与其它文档的关系

| 议题 | 本文档章节 | 依据的现有实现 / 文档 |
|---|---|---|
| Agent 分工 | §2.1 | 01-架构设计文档 §2.2, 02-详细设计文档 §2 |
| 反幻觉体系与硬规则 | §2.2 C5, §4.5 | 02-详细设计文档 §4, CriticAgent 三条硬规则 |
| 迭代取证-证伪 | §2.2 C5, §4.5 | 02-详细设计文档 §2.4, InvestigationService |
| 超时与降级 | §2.2 C6, §5.6 | 02-详细设计文档 §7, 05-关键议题补充 §1 |
| 兜底与 HITL | §2.2 C6, §6.3 | 05-关键议题补充 §1.1, §1.2 |
| 模型分档路由 | §3.2, §3.3 (Judge 选型) | 05-关键议题补充 §2, ModelTier |
| 观测埋点复用 | §2.2 各维度, §6.4 | RcaMetrics |
| E2E 测试骨架 | §5.4 | AlertRcaEndToEndIT (已有) |
