package com.tencent.rca.notify;

import com.tencent.rca.common.enums.ChannelType;
import com.tencent.rca.common.enums.SendStatus;
import com.tencent.rca.repository.NotificationRecordRepository;
import com.tencent.rca.repository.entity.NotificationRecordEntity;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 通知网关 (详细设计文档 3.2).
 * 按 channelType 建立渠道索引路由, 支持一次分发多个渠道. 单渠道失败不影响其他渠道;
 * 推送结果一律落库 notification_record. 推送失败绝不阻塞归因主流程(报告已生成即视为分析成功).
 */
@Slf4j
@Component
public class NotificationGateway {

    private final Map<ChannelType, NotificationChannel> channelIndex = new EnumMap<>(ChannelType.class);
    private final NotificationRecordRepository notificationRecordRepository;

    public NotificationGateway(List<NotificationChannel> channels,
                               NotificationRecordRepository notificationRecordRepository) {
        for (NotificationChannel channel : channels) {
            channelIndex.put(channel.channelType(), channel);
        }
        this.notificationRecordRepository = notificationRecordRepository;
    }

    /**
     * 向指定渠道分发通知.
     *
     * @param message  通知消息
     * @param channels 目标渠道列表
     * @return 各渠道发送结果
     */
    public Map<ChannelType, NotificationResult> dispatch(NotificationMessage message, List<ChannelType> channels) {
        Map<ChannelType, NotificationResult> results = new EnumMap<>(ChannelType.class);
        Long caseId = parseCaseId(message.caseId());
        for (ChannelType channelType : channels) {
            NotificationChannel channel = channelIndex.get(channelType);
            if (channel == null) {
                log.warn("渠道未注册, 跳过: {}", channelType);
                results.put(channelType, new NotificationResult(false, "渠道未注册"));
                continue;
            }
            NotificationResult result = safeSend(channel, message);
            results.put(channelType, result);
            persistRecord(caseId, channelType, result);
        }
        return results;
    }

    private NotificationResult safeSend(NotificationChannel channel, NotificationMessage message) {
        try {
            return channel.send(message);
        } catch (RuntimeException ex) {
            log.warn("渠道 {} 推送异常: {}", channel.channelType(), ex.getMessage());
            return new NotificationResult(false, "推送异常: " + ex.getMessage());
        }
    }

    private void persistRecord(Long caseId, ChannelType channelType, NotificationResult result) {
        if (caseId == null) {
            log.warn("caseId 非法, 跳过推送记录落库: channel={}", channelType);
            return;
        }
        try {
            NotificationRecordEntity record = new NotificationRecordEntity();
            record.setCaseId(caseId);
            record.setChannelType(channelType);
            record.setSendStatus(result.success() ? SendStatus.SUCCESS : SendStatus.FAILED);
            record.setFailReason(result.failReason());
            record.setRetryCount(0);
            record.setSentAt(LocalDateTime.now());
            notificationRecordRepository.save(record);
        } catch (RuntimeException ex) {
            log.warn("推送记录落库失败, 不影响主流程: channel={}, err={}", channelType, ex.getMessage());
        }
    }

    private Long parseCaseId(String caseId) {
        if (!StringUtils.hasText(caseId)) {
            return null;
        }
        try {
            return Long.parseLong(caseId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
