package com.tencent.rca.repository;

import com.tencent.rca.repository.entity.NotificationRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 报告推送记录仓储 (详细设计文档 3.2).
 */
public interface NotificationRecordRepository extends JpaRepository<NotificationRecordEntity, Long> {
}
