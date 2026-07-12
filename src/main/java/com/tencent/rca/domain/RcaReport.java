package com.tencent.rca.domain;

import com.tencent.rca.common.enums.ConfidenceLevel;
import java.util.List;

/**
 * 最终归因报告 (详细设计文档 3.1).
 *
 * @param caseId           案卷 ID
 * @param serviceName      服务名
 * @param conclusion       一句话根因结论
 * @param confidence       置信度
 * @param impactScope      影响范围
 * @param hypotheses       根因假设排序
 * @param timeline         时间线
 * @param callChainView    裁剪后的调用链视图
 * @param suggestedActions 建议动作
 * @param pendingItems     待确认项
 * @param degradeNote      降级说明 (无降级时为空)
 * @param fallback         是否为兜底报告 (详细设计文档 7.6)
 * @param markdownBody     渲染后的 Markdown 正文
 */
public record RcaReport(
        Long caseId,
        String serviceName,
        String conclusion,
        ConfidenceLevel confidence,
        String impactScope,
        List<RootCauseHypothesis> hypotheses,
        List<TimelineItem> timeline,
        String callChainView,
        List<String> suggestedActions,
        List<String> pendingItems,
        String degradeNote,
        boolean fallback,
        String markdownBody) {

    /**
     * 时间线条目.
     *
     * @param time  时间点
     * @param event 事件描述
     */
    public record TimelineItem(String time, String event) {
    }
}
