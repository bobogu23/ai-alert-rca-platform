package com.tencent.rca.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencent.rca.common.enums.FeedbackType;
import com.tencent.rca.repository.entity.AlertFeedbackEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FeedbackCredibilityScorer 单元测试: 校验可信度加权、共识度统计与高可信门槛.
 */
class FeedbackCredibilityScorerTest {

    private final FeedbackCredibilityScorer scorer = new FeedbackCredibilityScorer();

    @Test
    void shouldGiveHigherScoreToSeniorAndConsensus() {
        AlertFeedbackEntity senior = feedback("fp-1", FeedbackType.REJECTED, 3);
        AlertFeedbackEntity junior = feedback("fp-1", FeedbackType.REJECTED, 1);

        double seniorScore = scorer.score(senior, 3);
        double juniorScore = scorer.score(junior, 1);

        assertThat(seniorScore).isGreaterThan(juniorScore);
        // 资深 + 满共识应达到高可信门槛
        assertThat(scorer.isHighCredibility(senior, 3)).isTrue();
        // 新手 + 单条无共识应低于门槛
        assertThat(scorer.isHighCredibility(junior, 1)).isFalse();
    }

    @Test
    void shouldClampLevelAndConsensus() {
        AlertFeedbackEntity superSenior = feedback("fp-1", FeedbackType.REJECTED, 99);
        // 级别与共识都超上限, 分值不应超过 1
        assertThat(scorer.score(superSenior, 999)).isLessThanOrEqualTo(1.0);
    }

    @Test
    void shouldTreatNullLevelAsLowest() {
        AlertFeedbackEntity noLevel = feedback("fp-1", FeedbackType.REJECTED, null);
        double score = scorer.score(noLevel, 1);
        assertThat(score).isLessThan(FeedbackCredibilityScorer.HIGH_CREDIBILITY_THRESHOLD);
    }

    @Test
    void shouldCountConsensusBySameFingerprintAndType() {
        AlertFeedbackEntity target = feedback("fp-1", FeedbackType.REJECTED, 2);
        List<AlertFeedbackEntity> pool = List.of(
                target,
                feedback("fp-1", FeedbackType.REJECTED, 1),
                feedback("fp-1", FeedbackType.CONFIRMED, 1),
                feedback("fp-2", FeedbackType.REJECTED, 1));

        // 仅 fp-1 + REJECTED 计数 (target 自身 + 1 条) = 2
        assertThat(scorer.consensusCount(target, pool)).isEqualTo(2);
    }

    @Test
    void shouldReturnOneConsensusWhenFingerprintNull() {
        AlertFeedbackEntity target = feedback(null, FeedbackType.REJECTED, 2);
        assertThat(scorer.consensusCount(target, List.of(target))).isEqualTo(1);
    }

    private AlertFeedbackEntity feedback(String fingerprint, FeedbackType type, Integer level) {
        AlertFeedbackEntity entity = new AlertFeedbackEntity();
        entity.setFingerprint(fingerprint);
        entity.setFeedbackType(type);
        entity.setFeedbackerLevel(level);
        return entity;
    }
}
