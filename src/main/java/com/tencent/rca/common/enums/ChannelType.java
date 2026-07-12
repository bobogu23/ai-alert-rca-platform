package com.tencent.rca.common.enums;

/**
 * 报告推送渠道类型. 第一版仅启用 WECOM, 其余为预留扩展 (详细设计文档 3.2).
 */
public enum ChannelType {
    /** 企业微信群机器人. */
    WECOM,
    /** 预留: 邮件. */
    EMAIL,
    /** 预留: 短信. */
    SMS,
    /** 预留: 工单系统. */
    TICKET
}
