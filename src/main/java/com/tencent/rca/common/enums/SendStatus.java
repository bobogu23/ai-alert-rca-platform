package com.tencent.rca.common.enums;

/**
 * 报告推送记录状态 (对应 notification_record.send_status).
 */
public enum SendStatus {
    /** 推送成功. */
    SUCCESS,
    /** 推送失败. */
    FAILED,
    /** 重试中. */
    RETRYING
}
