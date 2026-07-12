package com.tencent.rca.notify;

import com.tencent.rca.common.enums.ChannelType;

/**
 * 通知渠道扩展接口 (详细设计文档 3.2).
 * 新增渠道只需实现本接口并注册为 Spring Bean, 无需改动 NotificationGateway.
 */
public interface NotificationChannel {

    /**
     * 渠道类型标识, 用于网关路由.
     *
     * @return 渠道类型
     */
    ChannelType channelType();

    /**
     * 发送通知.
     *
     * @param message 通知消息
     * @return 发送结果
     */
    NotificationResult send(NotificationMessage message);
}
