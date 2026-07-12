package com.tencent.rca.repository.entity;

import com.tencent.rca.common.enums.AlertSource;
import com.tencent.rca.common.enums.AlertType;
import com.tencent.rca.common.enums.CaseStatus;
import com.tencent.rca.common.enums.ConfidenceLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 告警案卷台账实体, 映射 alert_case 表 (详细设计文档 6.1、9).
 * 字段与 schema.sql 严格一致, version 采用乐观锁保证并发状态流转安全.
 */
@Getter
@Setter
@Entity
@Table(name = "alert_case")
public class AlertCaseEntity {

    /** 主键. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 告警指纹. */
    @Column(name = "fingerprint", nullable = false)
    private String fingerprint;

    /** 服务名. */
    @Column(name = "service_name", nullable = false)
    private String serviceName;

    /** 告警来源. */
    @Enumerated(EnumType.STRING)
    @Column(name = "alert_source", nullable = false)
    private AlertSource alertSource;

    /** 告警类型. */
    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type")
    private AlertType alertType;

    /** 案卷状态. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CaseStatus status;

    /** 首次出现时间. */
    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    /** 最近出现时间. */
    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /** 归并的原始告警数量. */
    @Column(name = "merged_alert_count")
    private Integer mergedAlertCount;

    /** 级联合并时指向的根因案卷 ID, 自身为根因案卷时为空. */
    @Column(name = "root_cause_case_id")
    private Long rootCauseCaseId;

    /** 根因结论摘要. */
    @Column(name = "root_cause_summary")
    private String rootCauseSummary;

    /** 置信度. */
    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_level")
    private ConfidenceLevel confidenceLevel;

    /** 降级/兜底说明. */
    @Column(name = "degrade_note")
    private String degradeNote;

    /** 报告存储地址. */
    @Column(name = "report_url")
    private String reportUrl;

    /** 乐观锁版本号. */
    @Version
    @Column(name = "version")
    private Integer version;

    /** 创建时间, 由数据库默认值维护. */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间, 由数据库默认值维护. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
