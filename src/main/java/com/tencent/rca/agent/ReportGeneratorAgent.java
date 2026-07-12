package com.tencent.rca.agent;

import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.Evidence;
import com.tencent.rca.domain.EvidenceBundle;
import com.tencent.rca.domain.RcaReport;
import com.tencent.rca.domain.RcaReport.TimelineItem;
import com.tencent.rca.domain.ReasoningResult;
import com.tencent.rca.domain.RootCauseHypothesis;
import com.tencent.rca.llm.LlmClient;
import com.tencent.rca.llm.ModelTier;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 报告生成 Agent (详细设计文档 2.1、3.1).
 * 将校验通过的推理结果与证据渲染为最终归因报告, 同时产出结构化字段与 Markdown 正文.
 * 防幻觉: 结论、证据链、调用链视图、时间线均由本地确定性组装, LLM 仅用于处置建议的语义润色;
 * 每条证据在报告中均附可追溯 sourceRef, 杜绝裸结论.
 * 容错: 建议动作的 LLM 生成失败时降级为本地默认建议, 不影响报告主体产出.
 */
@Slf4j
@Component
public class ReportGeneratorAgent {

    private static final String SYSTEM_PROMPT = """
            你是资深 SRE 运维专家. 基于已确认的根因结论, 给出可执行的处置建议.
            要求: 只输出针对该根因的具体操作步骤(如止损、扩容、回滚、限流、排查方向), 不得引入新的根因判断.
            输出 suggestedActions 字符串数组, 每条一句话.""";

    private final LlmClient llmClient;

    public ReportGeneratorAgent(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 生成最终归因报告.
     *
     * @param caseId      案卷 ID
     * @param alert       告警上下文
     * @param reasoning   根因推理结果
     * @param bundle      证据集合
     * @param degradeNote 上游降级说明 (无降级时为空)
     * @return 填充完整的归因报告
     */
    public RcaReport generate(Long caseId,
                              AlertContext alert,
                              ReasoningResult reasoning,
                              EvidenceBundle bundle,
                              String degradeNote) {
        List<RootCauseHypothesis> hypotheses = reasoning.hypotheses() == null
                ? List.of() : reasoning.hypotheses();
        log.info("[ReportGeneratorAgent] 输入: caseId={}, service={}, 假设数={}, 降级说明={}",
                caseId, alert.serviceName(), hypotheses.size(), degradeNote);
        RootCauseHypothesis primary = hypotheses.isEmpty() ? null : hypotheses.get(0);

        String conclusion = primary != null ? primary.summary() : "证据不足, 未能明确根因";
        ConfidenceLevel confidence = primary != null ? primary.confidence() : ConfidenceLevel.LOW;
        String impactScope = StringUtils.hasText(reasoning.impactScope())
                ? reasoning.impactScope() : "影响范围待确认";
        String callChainView = resolveCallChainView(bundle);
        List<TimelineItem> timeline = buildTimeline(alert, bundle);
        List<String> pendingItems = buildPendingItems(hypotheses, confidence);
        List<String> suggestedActions = buildSuggestedActions(conclusion, confidence);

        String markdownBody = renderMarkdown(alert, conclusion, confidence, impactScope,
                hypotheses, timeline, callChainView, suggestedActions, pendingItems, degradeNote);

        log.info("[ReportGeneratorAgent] 输出: caseId={}, 置信度={}, 建议动作数={}, 待确认项数={}, 报告字符数={}",
                caseId, confidence, suggestedActions.size(), pendingItems.size(), markdownBody.length());
        return new RcaReport(caseId, alert.serviceName(), conclusion, confidence, impactScope,
                hypotheses, timeline, callChainView, suggestedActions, pendingItems,
                degradeNote, false, markdownBody);
    }

    private String resolveCallChainView(EvidenceBundle bundle) {
        return bundle.logResult()
                .map(result -> StringUtils.hasText(result.prunedCallChain()) ? result.prunedCallChain() : "无")
                .orElse("无");
    }

    private List<TimelineItem> buildTimeline(AlertContext alert, EvidenceBundle bundle) {
        List<TimelineItem> timeline = new ArrayList<>();
        timeline.add(new TimelineItem(alert.timeWindow().start().toString(), "告警时间窗开始"));
        bundle.logResult().ifPresent(result -> result.topExceptionCategories().forEach(category ->
                timeline.add(new TimelineItem(alert.timeWindow().start().toString(),
                        String.format("异常聚类出现 %d 次: %s", category.count(), category.fingerprint())))));
        timeline.add(new TimelineItem(alert.timeWindow().end().toString(), "告警时间窗结束"));
        return timeline;
    }

    private List<String> buildPendingItems(List<RootCauseHypothesis> hypotheses, ConfidenceLevel confidence) {
        List<String> pendingItems = new ArrayList<>();
        for (RootCauseHypothesis hypothesis : hypotheses) {
            if (hypothesis.counterEvidenceOrGaps() != null) {
                pendingItems.addAll(hypothesis.counterEvidenceOrGaps());
            }
        }
        if (confidence == ConfidenceLevel.LOW) {
            pendingItems.add("根因置信度较低, 建议人工进一步排查确认");
        }
        return pendingItems;
    }

    private List<String> buildSuggestedActions(String conclusion, ConfidenceLevel confidence) {
        try {
            String userPrompt = "根因结论: " + conclusion + "\n置信度: " + confidence;
            SuggestedActionOutput output = llmClient.completeStructured(
                    ModelTier.REASONING, SYSTEM_PROMPT, userPrompt, SuggestedActionOutput.class);
            if (output.suggestedActions() != null && !output.suggestedActions().isEmpty()) {
                return output.suggestedActions();
            }
        } catch (RuntimeException ex) {
            log.warn("建议动作 LLM 生成失败, 降级为默认建议: {}", ex.getMessage());
        }
        return List.of("参考根因结论进行排查与止损", "如置信度不足, 请结合监控与日志人工复核");
    }

    private String renderMarkdown(AlertContext alert,
                                  String conclusion,
                                  ConfidenceLevel confidence,
                                  String impactScope,
                                  List<RootCauseHypothesis> hypotheses,
                                  List<TimelineItem> timeline,
                                  String callChainView,
                                  List<String> suggestedActions,
                                  List<String> pendingItems,
                                  String degradeNote) {
        StringBuilder builder = new StringBuilder();
        builder.append("## 根因结论\n")
                .append("- 服务: ").append(alert.serviceName()).append('\n')
                .append("- 结论: ").append(conclusion).append('\n')
                .append("- 置信度: ").append(confidence).append('\n')
                .append("- 影响范围: ").append(impactScope).append('\n');
        if (StringUtils.hasText(degradeNote)) {
            builder.append("- 降级说明: ").append(degradeNote).append('\n');
        }
        appendEvidenceChain(builder, hypotheses);
        appendTimeline(builder, timeline);
        builder.append("\n## 调用链\n").append(callChainView).append('\n');
        appendHypotheses(builder, hypotheses);
        appendList(builder, "\n## 建议动作\n", suggestedActions);
        appendList(builder, "\n## 待确认项\n", pendingItems);
        return builder.toString();
    }

    private void appendEvidenceChain(StringBuilder builder, List<RootCauseHypothesis> hypotheses) {
        builder.append("\n## 证据链\n");
        boolean hasEvidence = false;
        for (RootCauseHypothesis hypothesis : hypotheses) {
            if (hypothesis.supportingEvidence() == null) {
                continue;
            }
            for (Evidence evidence : hypothesis.supportingEvidence()) {
                hasEvidence = true;
                builder.append("- [").append(evidence.type()).append("] ")
                        .append(evidence.description())
                        .append(" (来源: ").append(evidence.sourceRef()).append(")\n");
            }
        }
        if (!hasEvidence) {
            builder.append("(无结构化证据)\n");
        }
    }

    private void appendTimeline(StringBuilder builder, List<TimelineItem> timeline) {
        builder.append("\n## 时间线\n");
        for (TimelineItem item : timeline) {
            builder.append("- ").append(item.time()).append(" ").append(item.event()).append('\n');
        }
    }

    private void appendHypotheses(StringBuilder builder, List<RootCauseHypothesis> hypotheses) {
        builder.append("\n## 假设排序\n");
        if (hypotheses.isEmpty()) {
            builder.append("(无可用假设)\n");
            return;
        }
        for (int i = 0; i < hypotheses.size(); i++) {
            RootCauseHypothesis hypothesis = hypotheses.get(i);
            builder.append(i + 1).append(". ").append(hypothesis.summary())
                    .append(" (置信度 ").append(hypothesis.confidence()).append(")\n");
        }
    }

    private void appendList(StringBuilder builder, String title, List<String> items) {
        builder.append(title);
        if (items.isEmpty()) {
            builder.append("(无)\n");
            return;
        }
        for (String item : items) {
            builder.append("- ").append(item).append('\n');
        }
    }
}
