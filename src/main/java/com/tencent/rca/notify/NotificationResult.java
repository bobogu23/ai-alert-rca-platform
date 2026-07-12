package com.tencent.rca.notify;

/**
 * 通知发送结果 (详细设计文档 3.2).
 *
 * @param success    是否发送成功
 * @param failReason 失败原因 (成功时为空)
 */
public record NotificationResult(
        boolean success,
        String failReason) {
}
