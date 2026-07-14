package com.tencent.rca.governance;

import com.tencent.rca.common.enums.FeedbackType;
import com.tencent.rca.repository.AlertFeedbackRepository;
import com.tencent.rca.repository.entity.AlertFeedbackEntity;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 反馈聚合分析服务 (在线自我完善方案 07 文档 3.2、6.2).
 * 按错误类型/真实根因方向/服务/指纹多维聚合反馈, 并计算采纳率等飞轮健康度指标, 供看板与经验沉淀决策使用.
 * 纯统计, 不改动任何状态; 全部为只读查询.
 */
@Slf4j
@Service
public class FeedbackAnalysisService {

    private static final String UNSPECIFIED = "UNSPECIFIED";
    private static final int TOP_BADCASE_LIMIT = 20;

    private final AlertFeedbackRepository feedbackRepository;

    public FeedbackAnalysisService(AlertFeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    /**
     * 计算指定窗口内的飞轮健康度指标.
     *
     * @param windowDays 统计窗口天数
     * @return 飞轮指标
     */
    public FlywheelMetrics computeFlywheelMetrics(int windowDays) {
        LocalDateTime after = LocalDateTime.now().minusDays(Math.max(1, windowDays));
        long total = feedbackRepository.countByFeedbackAtAfter(after);
        long confirmed = feedbackRepository.countByFeedbackTypeAndFeedbackAtAfter(FeedbackType.CONFIRMED, after);
        long partial = feedbackRepository.countByFeedbackTypeAndFeedbackAtAfter(FeedbackType.PARTIAL, after);
        long rejected = feedbackRepository.countByFeedbackTypeAndFeedbackAtAfter(FeedbackType.REJECTED, after);
        long falsePositive =
                feedbackRepository.countByFeedbackTypeAndFeedbackAtAfter(FeedbackType.FALSE_POSITIVE, after);
        double adoptionRate = total == 0 ? 0.0 : (double) confirmed / total;
        return new FlywheelMetrics(windowDays, total, confirmed, partial, rejected, falsePositive, adoptionRate);
    }

    /**
     * 对指定窗口内的反馈做多维聚合分析.
     *
     * @param windowDays 统计窗口天数
     * @return 聚合结果
     */
    public FeedbackAggregation aggregate(int windowDays) {
        LocalDateTime after = LocalDateTime.now().minusDays(Math.max(1, windowDays));
        List<AlertFeedbackEntity> feedbacks = feedbackRepository.findByFeedbackAtAfter(after);

        Map<String, Long> byErrorCategory = feedbacks.stream()
                .collect(Collectors.groupingBy(
                        f -> f.getErrorCategory() == null ? UNSPECIFIED : f.getErrorCategory().name(),
                        Collectors.counting()));

        Map<String, Long> byActualDirection = feedbacks.stream()
                .filter(f -> f.getActualRootCauseDirection() != null)
                .collect(Collectors.groupingBy(
                        f -> f.getActualRootCauseDirection().name(), Collectors.counting()));

        // 服务维度只统计诊断被打回的反馈 (驳回/部分正确), 反映哪些服务归因准确率低
        Map<String, Long> byService = feedbacks.stream()
                .filter(this::isNegative)
                .filter(f -> f.getServiceName() != null)
                .collect(Collectors.groupingBy(AlertFeedbackEntity::getServiceName, Collectors.counting()));

        Map<String, Long> topBadcase = feedbacks.stream()
                .filter(f -> f.getFeedbackType() == FeedbackType.REJECTED && f.getFingerprint() != null)
                .collect(Collectors.groupingBy(AlertFeedbackEntity::getFingerprint, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP_BADCASE_LIMIT)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        return new FeedbackAggregation(windowDays, byErrorCategory, byActualDirection, byService, topBadcase);
    }

    private boolean isNegative(AlertFeedbackEntity feedback) {
        return feedback.getFeedbackType() == FeedbackType.REJECTED
                || feedback.getFeedbackType() == FeedbackType.PARTIAL;
    }
}
