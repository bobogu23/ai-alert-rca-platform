package com.tencent.rca.repository;

import com.tencent.rca.common.enums.FeedbackType;
import com.tencent.rca.repository.entity.AlertFeedbackEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 人工反馈仓储 (在线自我完善方案 07 文档 2.3、3).
 */
public interface AlertFeedbackRepository extends JpaRepository<AlertFeedbackEntity, Long> {

    /**
     * 查询指定案卷的全部反馈, 按时间倒序.
     *
     * @param caseId 案卷 ID
     * @return 反馈列表
     */
    List<AlertFeedbackEntity> findByCaseIdOrderByFeedbackAtDesc(Long caseId);

    /**
     * 统计指定时间之后某反馈类型的数量, 供采纳率等飞轮指标计算.
     *
     * @param feedbackType 反馈类型
     * @param after        反馈时间下界
     * @return 反馈数量
     */
    long countByFeedbackTypeAndFeedbackAtAfter(FeedbackType feedbackType, LocalDateTime after);

    /**
     * 统计指定时间之后的反馈总数.
     *
     * @param after 反馈时间下界
     * @return 反馈总数
     */
    long countByFeedbackAtAfter(LocalDateTime after);

    /**
     * 查询指定时间之后的全部反馈, 供聚合分析与 badcase 导出.
     *
     * @param after 反馈时间下界
     * @return 反馈列表
     */
    List<AlertFeedbackEntity> findByFeedbackAtAfter(LocalDateTime after);

    /**
     * 查询指定时间之后、指定反馈类型、且尚未提炼为经验的反馈, 供 badcase 导出回归集.
     *
     * @param feedbackType 反馈类型
     * @param after        反馈时间下界
     * @param adopted      是否已提炼为经验
     * @return 反馈列表
     */
    List<AlertFeedbackEntity> findByFeedbackTypeAndFeedbackAtAfterAndAdoptedToExperience(
            FeedbackType feedbackType, LocalDateTime after, Boolean adopted);
}
