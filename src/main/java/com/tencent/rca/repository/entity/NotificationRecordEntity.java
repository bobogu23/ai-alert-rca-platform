package com.tencent.rca.repository.entity;

import com.tencent.rca.common.enums.ChannelType;
import com.tencent.rca.common.enums.SendStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 报告推送记录实体, 映射 notification_record 表 (详细设计文档 3.2、9).
 */
@Getter
@Setter
@Entity
@Table(name = "notification_record")
public class NotificationRecordEntity {

    /** 主键. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的案卷 ID. */
    @Column(name = "case_id", nullable = false)
    private Long caseId;

    /** 推送渠道. */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false)
    private ChannelType channelType;

    /** 推送状态. */
    @Enumerated(EnumType.STRING)
    @Column(name = "send_status", nullable = false)
    private SendStatus sendStatus;

    /** 失败原因. */
    @Column(name = "fail_reason")
    private String failReason;

    /** 重试次数. */
    @Column(name = "retry_count")
    private Integer retryCount;

    /** 发送时间. */
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** 创建时间, 由数据库默认值维护. */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
