package com.tencent.rca.repository.entity;

import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.common.enums.FeedbackErrorCategory;
import com.tencent.rca.common.enums.FeedbackType;
import com.tencent.rca.common.enums.SuspectedCause;
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
 * 人工反馈台账实体, 映射 alert_feedback 表 (在线自我完善方案 07 文档 2.3).
 * 记录一次归因报告的人工确认结果 + 系统当时的诊断快照 + 人工纠错内容, 作为经验飞轮的原始燃料.
 * 字段与 schema.sql 严格一致.
 */
@Getter
@Setter
@Entity
@Table(name = "alert_feedback")
public class AlertFeedbackEntity {

    /** 主键. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联案卷 ID. */
    @Column(name = "case_id", nullable = false)
    private Long caseId;

    /** 告警指纹, 用于同类告警经验复用. */
    @Column(name = "fingerprint")
    private String fingerprint;

    /** 服务名, 冗余自案卷, 用于按服务聚合归因准确率. */
    @Column(name = "service_name")
    private String serviceName;

    /** 反馈类型. */
    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_type", nullable = false)
    private FeedbackType feedbackType;

    // === 系统当时的诊断快照 (用于对比学习) ===

    /** AI 给出的根因结论摘要. */
    @Column(name = "ai_root_cause_summary")
    private String aiRootCauseSummary;

    /** AI 给出的置信度. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ai_confidence")
    private ConfidenceLevel aiConfidence;

    // === 人工纠错内容 (REJECTED/PARTIAL 时填写) ===

    /** 真实根因方向. */
    @Enumerated(EnumType.STRING)
    @Column(name = "actual_root_cause_direction")
    private SuspectedCause actualRootCauseDirection;

    /** 真实根因描述. */
    @Column(name = "actual_root_cause_summary")
    private String actualRootCauseSummary;

    /** 正确的证据线索 (如"应看 GC 指标"). */
    @Column(name = "correct_evidence_hint")
    private String correctEvidenceHint;

    /** 错误类型. */
    @Enumerated(EnumType.STRING)
    @Column(name = "error_category")
    private FeedbackErrorCategory errorCategory;

    /** 反馈人自由建议. */
    @Column(name = "suggestion")
    private String suggestion;

    // === 反馈治理字段 ===

    /** 反馈人标识, 用于可信度加权. */
    @Column(name = "feedback_by")
    private String feedbackBy;

    /** 反馈人级别, 资深 SRE 权重更高 (1~3, 默认 1). */
    @Column(name = "feedbacker_level")
    private Integer feedbackerLevel;

    /** 是否已提炼为经验资产, 默认 false. */
    @Column(name = "adopted_to_experience")
    private Boolean adoptedToExperience;

    /** 反馈时间. */
    @Column(name = "feedback_at", nullable = false)
    private LocalDateTime feedbackAt;
}
