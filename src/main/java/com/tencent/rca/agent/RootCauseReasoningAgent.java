package com.tencent.rca.agent;

import com.tencent.rca.agent.ReasoningLlmOutput.HypothesisDraft;
import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.domain.Evidence;
import com.tencent.rca.domain.EvidenceBundle;
import com.tencent.rca.domain.ReasoningResult;
import com.tencent.rca.domain.RootCauseHypothesis;
import com.tencent.rca.llm.LlmClient;
import com.tencent.rca.llm.ModelTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 根因推理 Agent (详细设计文档 2.1、2.3).
 * 融合日志/Trace-Metric/代码三路证据做跨证据推理, 输出按置信度排序的根因假设.
 * 防幻觉: 给每条真实证据编号后喂给 LLM, LLM 仅能引用编号, 本地再映射回真实 Evidence, 无效引用丢弃 (详细设计文档 4.1).
 * LLM 调用异常不在此捕获, 交由 Orchestrator 按容错策略处理 (详细设计文档 7.4).
 */
@Slf4j
@Component
public class RootCauseReasoningAgent {

    private static final int MAX_HYPOTHESES = 3;

    private static final String SYSTEM_PROMPT = """
            你是资深 SRE 根因分析专家. 你只能基于提供的证据进行推理, 严禁编造证据中未出现的数值、日志或指标.
            任务: 输出按可能性排序的根因假设列表(最多 3 个), 每个假设包含:
            - summary: 一句话根因;
            - confidence: 取值 HIGH/MEDIUM/LOW, 仅当多个独立证据一致且时间线吻合时才可用 HIGH, 单一证据最多 MEDIUM;
            - evidenceRefs: 支撑该假设的证据编号(如 E1、E2), 只能引用给定编号, 不得编造;
            - counterEvidenceOrGaps: 反证或未确认项.
            并给出 impactScope 影响范围. 若证据不足, 使用低置信度并在 counterEvidenceOrGaps 中说明.""";

    private final LlmClient llmClient;

    public RootCauseReasoningAgent(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 基于证据集合推理根因.
     *
     * @param bundle 证据集合
     * @return 根因假设列表与影响范围
     */
    public ReasoningResult reason(EvidenceBundle bundle) {
        List<Evidence> allEvidences = bundle.allEvidences();
        log.info("[RootCauseReasoningAgent] 输入: service={}, 证据总数={}",
                bundle.alertContext().serviceName(), allEvidences.size());
        Map<String, Evidence> refMap = buildRefMap(allEvidences);
        String userPrompt = buildUserPrompt(bundle, refMap);

        ReasoningLlmOutput output = llmClient.completeStructured(
                ModelTier.REASONING, SYSTEM_PROMPT, userPrompt, ReasoningLlmOutput.class);

        List<RootCauseHypothesis> hypotheses = mapHypotheses(output, refMap);
        String impactScope = StringUtils.hasText(output.impactScope())
                ? output.impactScope()
                : "服务 " + bundle.alertContext().serviceName();
        log.info("[RootCauseReasoningAgent] 输出: 假设数={}, 首假设置信度={}, 影响范围={}",
                hypotheses.size(),
                hypotheses.isEmpty() ? "无" : hypotheses.get(0).confidence(),
                impactScope);
        return new ReasoningResult(hypotheses, impactScope);
    }

    private Map<String, Evidence> buildRefMap(List<Evidence> allEvidences) {
        Map<String, Evidence> refMap = new LinkedHashMap<>();
        for (int i = 0; i < allEvidences.size(); i++) {
            refMap.put("E" + (i + 1), allEvidences.get(i));
        }
        return refMap;
    }

    private List<RootCauseHypothesis> mapHypotheses(ReasoningLlmOutput output, Map<String, Evidence> refMap) {
        List<RootCauseHypothesis> hypotheses = new ArrayList<>();
        if (output.hypotheses() == null) {
            return hypotheses;
        }
        for (HypothesisDraft draft : output.hypotheses()) {
            if (hypotheses.size() >= MAX_HYPOTHESES) {
                break;
            }
            List<Evidence> supporting = mapEvidences(draft.evidenceRefs(), refMap);
            List<String> gaps = draft.counterEvidenceOrGaps() != null ? draft.counterEvidenceOrGaps() : List.of();
            hypotheses.add(new RootCauseHypothesis(
                    draft.summary(), parseConfidence(draft.confidence()), supporting, gaps));
        }
        return hypotheses;
    }

    private List<Evidence> mapEvidences(List<String> refs, Map<String, Evidence> refMap) {
        List<Evidence> supporting = new ArrayList<>();
        if (refs == null) {
            return supporting;
        }
        for (String ref : refs) {
            Evidence evidence = refMap.get(ref == null ? "" : ref.trim());
            if (evidence != null) {
                supporting.add(evidence);
            } else {
                log.debug("忽略无效证据引用: {}", ref);
            }
        }
        return supporting;
    }

    private ConfidenceLevel parseConfidence(String value) {
        if (!StringUtils.hasText(value)) {
            return ConfidenceLevel.LOW;
        }
        try {
            return ConfidenceLevel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ConfidenceLevel.LOW;
        }
    }

    private String buildUserPrompt(EvidenceBundle bundle, Map<String, Evidence> refMap) {
        StringBuilder builder = new StringBuilder();
        builder.append("告警信息:\n")
                .append("服务: ").append(bundle.alertContext().serviceName()).append('\n')
                .append("类型: ").append(bundle.alertContext().alertType()).append('\n')
                .append("时间窗口: ").append(bundle.alertContext().timeWindow().start())
                .append(" ~ ").append(bundle.alertContext().timeWindow().end()).append("\n\n");

        appendLogSection(builder, bundle);
        appendTraceSection(builder, bundle);
        appendCodeSection(builder, bundle);
        appendEvidenceCatalog(builder, refMap);
        return builder.toString();
    }

    private void appendLogSection(StringBuilder builder, EvidenceBundle bundle) {
        bundle.logResult().ifPresent(result -> {
            builder.append("[日志分析]\n")
                    .append("调用链: ").append(orNone(result.prunedCallChain())).append('\n')
                    .append("疑似依赖: ").append(orNone(result.suspectedDependency())).append('\n');
            if (result.degraded()) {
                builder.append("(降级: ").append(orNone(result.degradeNote())).append(")\n");
            }
            builder.append('\n');
        });
    }

    private void appendTraceSection(StringBuilder builder, EvidenceBundle bundle) {
        bundle.traceMetricResult().ifPresent(result -> {
            builder.append("[Trace/Metric 分析]\n")
                    .append("瓶颈类型: ").append(orNone(result.bottleneckType())).append('\n')
                    .append("指标摘要: ").append(orNone(result.metricSummary())).append('\n')
                    .append("Top 慢 span: ").append(String.join(", ", result.topSlowSpans())).append('\n');
            if (result.degraded()) {
                builder.append("(降级: ").append(orNone(result.degradeNote())).append(")\n");
            }
            builder.append('\n');
        });
    }

    private void appendCodeSection(StringBuilder builder, EvidenceBundle bundle) {
        bundle.codeContextResult().ifPresent(result -> {
            builder.append("[代码上下文]\n")
                    .append("配置隐患: ").append(String.join("; ", result.riskPoints())).append('\n')
                    .append("最近发布: ").append(String.join("; ", result.recentChanges())).append('\n');
            if (result.degraded()) {
                builder.append("(降级: ").append(orNone(result.degradeNote())).append(")\n");
            }
            builder.append('\n');
        });
    }

    private void appendEvidenceCatalog(StringBuilder builder, Map<String, Evidence> refMap) {
        builder.append("证据清单(引用时只能使用以下编号):\n");
        if (refMap.isEmpty()) {
            builder.append("(无结构化证据, 请基于上述各维度结论审慎推理并降低置信度)\n");
            return;
        }
        for (Map.Entry<String, Evidence> entry : refMap.entrySet()) {
            Evidence evidence = entry.getValue();
            builder.append(entry.getKey()).append(" [").append(evidence.type()).append("] ")
                    .append(evidence.description()).append(" (来源: ").append(evidence.sourceRef()).append(")\n");
        }
    }

    private String orNone(String value) {
        return StringUtils.hasText(value) ? value : "无";
    }
}
