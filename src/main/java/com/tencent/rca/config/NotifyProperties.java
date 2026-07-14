package com.tencent.rca.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通知渠道配置, 对应 application.yml 中的 rca.notify.* 节点.
 */
@Data
@ConfigurationProperties(prefix = "rca.notify")
public class NotifyProperties {

    /** 企业微信渠道配置. */
    private Wecom wecom = new Wecom();

    /**
     * 反馈落地页基础 URL, 用于在报告中渲染人工反馈入口 (在线自我完善方案 07 文档 2.5).
     * 形如 https://rca.example.com/feedback, 拼接 caseId 后引导 SRE 就地反馈. 为空则不渲染反馈区块.
     */
    private String feedbackBaseUrl;

    /** 企业微信群机器人配置. */
    @Data
    public static class Wecom {
        /** 是否启用. */
        private boolean enabled = true;
        /** 群机器人 Webhook 地址. */
        private String webhookUrl;
        /** 推送失败最大重试次数. */
        private int maxRetries = 3;
    }
}
