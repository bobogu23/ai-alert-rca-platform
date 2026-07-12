package com.tencent.rca.repository.entity;

import com.tencent.rca.common.enums.SuppressionRuleType;
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
 * 告警抑制规则实体, 映射 suppression_rule 表 (详细设计文档 6.2、9).
 * match_condition 以 JSON 字符串存储, 由 SuppressionEngine 解析.
 */
@Getter
@Setter
@Entity
@Table(name = "suppression_rule")
public class SuppressionRuleEntity {

    /** 主键. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则类型. */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false)
    private SuppressionRuleType ruleType;

    /** 匹配条件(JSON 字符串). */
    @Column(name = "match_condition", nullable = false)
    private String matchCondition;

    /** 生效开始时间. */
    @Column(name = "effective_start")
    private LocalDateTime effectiveStart;

    /** 生效结束时间. */
    @Column(name = "effective_end")
    private LocalDateTime effectiveEnd;

    /** 是否启用. */
    @Column(name = "enabled")
    private Boolean enabled;

    /** 创建人. */
    @Column(name = "created_by")
    private String createdBy;

    /** 创建时间, 由数据库默认值维护. */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
