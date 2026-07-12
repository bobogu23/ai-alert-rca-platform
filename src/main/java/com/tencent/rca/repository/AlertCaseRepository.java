package com.tencent.rca.repository;

import com.tencent.rca.common.enums.CaseStatus;
import com.tencent.rca.repository.entity.AlertCaseEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 告警案卷仓储 (详细设计文档 6.1).
 */
public interface AlertCaseRepository extends JpaRepository<AlertCaseEntity, Long> {

    /**
     * 按指纹查询处于指定状态集合中的最近一条活跃案卷, 用于短窗去重归并.
     *
     * @param fingerprint 告警指纹
     * @param statuses    活跃状态集合
     * @return 最近一条匹配案卷
     */
    Optional<AlertCaseEntity> findFirstByFingerprintAndStatusInOrderByLastSeenAtDesc(
            String fingerprint, Collection<CaseStatus> statuses);

    /**
     * 查询时间窗内处于指定状态集合的案卷, 供级联根因合并使用.
     *
     * @param statuses 状态集合
     * @param after    最近出现时间下界
     * @return 案卷列表
     */
    List<AlertCaseEntity> findByStatusInAndLastSeenAtAfter(Collection<CaseStatus> statuses, LocalDateTime after);
}
