package com.tencent.rca.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 人工反馈请求 (详细设计文档 4.6).
 *
 * @param verdict 反馈结论
 * @param comment 补充说明 (可为空)
 */
public record FeedbackRequest(
        @NotNull FeedbackVerdict verdict,
        String comment) {
}
