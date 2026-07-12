package com.tencent.rca.domain;

import java.util.List;
import java.util.Optional;

/**
 * 证据集合, 作为 RootCauseReasoningAgent 的输入 (详细设计文档 2.3).
 *
 * @param alertContext      告警上下文
 * @param logResult         日志分析结果 (可能为空, 表示该维度失败降级)
 * @param traceMetricResult Trace/Metric 分析结果 (按需触发)
 * @param codeContextResult 代码上下文结果 (可能为空)
 */
public record EvidenceBundle(
        AlertContext alertContext,
        Optional<LogAnalysisResult> logResult,
        Optional<TraceMetricResult> traceMetricResult,
        Optional<CodeContextResult> codeContextResult) {

    /**
     * 汇总所有 Agent 产出的证据, 供 Critic 做引用真伪校验 (详细设计文档 4.3).
     *
     * @return 全部证据列表
     */
    public List<Evidence> allEvidences() {
        java.util.List<Evidence> all = new java.util.ArrayList<>();
        logResult.ifPresent(r -> all.addAll(r.evidences()));
        traceMetricResult.ifPresent(r -> all.addAll(r.evidences()));
        codeContextResult.ifPresent(r -> all.addAll(r.evidences()));
        return all;
    }

    /**
     * 判断是否至少有一路证据源可用. 全部失败时应走全局兜底 (详细设计文档 7.6).
     *
     * @return 存在任一可用证据源时返回 true
     */
    public boolean hasAnyEvidence() {
        return logResult.isPresent() || traceMetricResult.isPresent() || codeContextResult.isPresent();
    }
}
