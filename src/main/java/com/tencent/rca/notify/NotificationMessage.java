package com.tencent.rca.notify;

import com.tencent.rca.common.enums.ConfidenceLevel;

/**
 * 通知消息载体 (详细设计文档 3.2).
 *
 * @param caseId       案卷 ID
 * @param title        消息标题
 * @param summary      结论摘要
 * @param confidence   置信度, 用于渠道侧视觉区分
 * @param reportUrl    报告访问地址 (可为空)
 * @param markdownBody 报告 Markdown 正文
 */
public record NotificationMessage(
        String caseId,
        String title,
        String summary,
        ConfidenceLevel confidence,
        String reportUrl,
        String markdownBody) {
}
