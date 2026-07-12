package com.tencent.rca.report;

import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.Evidence;
import com.tencent.rca.domain.EvidenceBundle;
import com.tencent.rca.domain.RcaReport;
import com.tencent.rca.domain.RcaReport.TimelineItem;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 全局兜底报告构建器 (详细设计文档 7.6).
 * 当分析无法正常完成时(整体超时 / LLM 完全不可用 / 三路证据源全失败)产出兜底报告, 保证系统不静默失败.
 * 纯本地渲染, 不依赖任何外部调用(含 LLM), 因此在极端故障场景仍可稳定产出.
 * 案卷状态由调用方置为 ANALYZE_FAILED.
 */
@Component
public class FallbackReportBuilder {

    private static final String FALLBACK_CONCLUSION = "系统未能完成自动归因, 请人工排查";

    /**
     * 构建兜底报告.
     *
     * @param caseId        案卷 ID
     * @param alert         告警上下文
     * @param partialBundle 已成功采集到的部分证据集合 (可能无任何证据)
     * @param failReason    失败原因说明
     * @return 兜底报告 (fallback=true, confidence=LOW)
     */
    public RcaReport buildFallback(Long caseId,
                                   AlertContext alert,
                                   EvidenceBundle partialBundle,
                                   String failReason) {
        List<Evidence> evidences = partialBundle == null ? List.of() : partialBundle.allEvidences();
        List<TimelineItem> timeline = buildTimeline(alert);
        String markdownBody = renderMarkdown(alert, evidences, failReason);
        return new RcaReport(
                caseId,
                alert.serviceName(),
                FALLBACK_CONCLUSION,
                ConfidenceLevel.LOW,
                "影响范围未能确认",
                List.of(),
                timeline,
                "无",
                List.of("请结合告警原文与已采集证据人工排查"),
                List.of("自动归因未完成, 需人工介入"),
                failReason,
                true,
                markdownBody);
    }

    private List<TimelineItem> buildTimeline(AlertContext alert) {
        List<TimelineItem> timeline = new ArrayList<>();
        timeline.add(new TimelineItem(alert.timeWindow().start().toString(), "告警时间窗开始"));
        timeline.add(new TimelineItem(alert.timeWindow().end().toString(), "告警时间窗结束"));
        return timeline;
    }

    private String renderMarkdown(AlertContext alert, List<Evidence> evidences, String failReason) {
        StringBuilder builder = new StringBuilder();
        builder.append("## 自动归因未完成\n")
                .append("- 服务: ").append(alert.serviceName()).append('\n')
                .append("- 结论: ").append(FALLBACK_CONCLUSION).append('\n')
                .append("- 置信度: ").append(ConfidenceLevel.LOW).append('\n')
                .append("- 失败原因: ").append(StringUtils.hasText(failReason) ? failReason : "未知").append('\n');

        builder.append("\n## 告警原文摘要\n")
                .append("- 告警 ID: ").append(alert.alertId()).append('\n')
                .append("- 告警来源: ").append(alert.alertSource()).append('\n')
                .append("- 告警类型: ").append(alert.alertType()).append('\n')
                .append("- 时间窗: ").append(alert.timeWindow().start())
                .append(" ~ ").append(alert.timeWindow().end()).append('\n');

        builder.append("\n## 已采集证据快照\n");
        if (evidences.isEmpty()) {
            builder.append("(未采集到任何证据)\n");
        } else {
            for (Evidence evidence : evidences) {
                builder.append("- [").append(evidence.type()).append("] ")
                        .append(evidence.description())
                        .append(" (来源: ").append(evidence.sourceRef()).append(")\n");
            }
        }
        return builder.toString();
    }
}
