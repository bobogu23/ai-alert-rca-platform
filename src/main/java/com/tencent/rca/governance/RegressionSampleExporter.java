package com.tencent.rca.governance;

import com.tencent.rca.common.enums.FeedbackType;
import com.tencent.rca.repository.AlertFeedbackRepository;
import com.tencent.rca.repository.entity.AlertFeedbackEntity;
import com.tencent.rca.security.SensitiveDataMasker;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 回归集样本导出器 (在线自我完善方案 07 文档 3.3).
 * 把高可信的驳回/部分正确 badcase 反馈, 经脱敏后导出为回归集标注样本, 供 06 测评方案防回归使用.
 * 导出后将反馈标记 adoptedToExperience=true, 避免重复导出. 脱敏复用在线链路同一组件, 保证分布一致.
 */
@Slf4j
@Service
public class RegressionSampleExporter {

    private final AlertFeedbackRepository feedbackRepository;
    private final FeedbackCredibilityScorer credibilityScorer;
    private final SensitiveDataMasker masker;

    public RegressionSampleExporter(AlertFeedbackRepository feedbackRepository,
                                    FeedbackCredibilityScorer credibilityScorer,
                                    SensitiveDataMasker masker) {
        this.feedbackRepository = feedbackRepository;
        this.credibilityScorer = credibilityScorer;
        this.masker = masker;
    }

    /**
     * 导出指定窗口内、未导出过的高可信 REJECTED badcase 为回归样本, 并标记已导出.
     *
     * @param windowDays 统计窗口天数
     * @return 导出的回归样本列表
     */
    public List<RegressionSample> exportRejectedBadcases(int windowDays) {
        LocalDateTime after = LocalDateTime.now().minusDays(Math.max(1, windowDays));
        List<AlertFeedbackEntity> candidates =
                feedbackRepository.findByFeedbackTypeAndFeedbackAtAfterAndAdoptedToExperience(
                        FeedbackType.REJECTED, after, false);

        List<RegressionSample> samples = new ArrayList<>();
        List<AlertFeedbackEntity> exported = new ArrayList<>();
        for (AlertFeedbackEntity feedback : candidates) {
            int consensus = credibilityScorer.consensusCount(feedback, candidates);
            double credibility = credibilityScorer.score(feedback, consensus);
            if (credibility < FeedbackCredibilityScorer.HIGH_CREDIBILITY_THRESHOLD) {
                continue;
            }
            samples.add(toSample(feedback, credibility));
            feedback.setAdoptedToExperience(true);
            exported.add(feedback);
        }
        if (!exported.isEmpty()) {
            feedbackRepository.saveAll(exported);
        }
        log.info("回归集导出: 窗口 {} 天, 候选 {} 条, 导出 {} 条", windowDays, candidates.size(), samples.size());
        return samples;
    }

    private RegressionSample toSample(AlertFeedbackEntity feedback, double credibility) {
        return new RegressionSample(
                "regression-" + feedback.getId(),
                feedback.getId(),
                feedback.getFingerprint(),
                feedback.getServiceName(),
                masker.mask(feedback.getAiRootCauseSummary()),
                feedback.getAiConfidence() == null ? null : feedback.getAiConfidence().name(),
                feedback.getActualRootCauseDirection() == null
                        ? null : feedback.getActualRootCauseDirection().name(),
                masker.mask(feedback.getActualRootCauseSummary()),
                masker.mask(feedback.getCorrectEvidenceHint()),
                feedback.getErrorCategory() == null ? null : feedback.getErrorCategory().name(),
                credibility);
    }
}
