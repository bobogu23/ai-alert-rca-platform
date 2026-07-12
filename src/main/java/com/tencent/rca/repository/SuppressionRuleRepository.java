package com.tencent.rca.repository;

import com.tencent.rca.repository.entity.SuppressionRuleEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 告警抑制规则仓储 (详细设计文档 6.2).
 */
public interface SuppressionRuleRepository extends JpaRepository<SuppressionRuleEntity, Long> {

    /**
     * 查询所有启用的抑制规则.
     *
     * @return 启用状态的规则列表
     */
    List<SuppressionRuleEntity> findByEnabledTrue();
}
