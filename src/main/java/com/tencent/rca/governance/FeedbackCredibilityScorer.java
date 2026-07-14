package com.tencent.rca.governance;

import com.tencent.rca.repository.entity.AlertFeedbackEntity;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 反馈可信度打分器 (在线自我完善方案 07 文档 3.1).
 * 对单条反馈按 "反馈人级别 + 同类共识度" 加权计算可信度分 (0~1), 供经验沉淀/badcase 导出做门槛过滤.
 * 纯确定性计算, 不依赖 LLM. 事后验证权重待后续版本接入故障复盘系统后补充.
 */
@Component
public class FeedbackCredibilityScorer {

    /** 反馈人级别权重占比. */
    private static final double W_LEVEL = 0.6;
    /** 同类共识度权重占比. */
    private static final double W_CONSENSUS = 0.4;
    /** 反馈人级别上限 (1~3), 用于归一化. */
    private static final int MAX_LEVEL = 3;
    /** 达到满共识所需的同类一致反馈数. */
    private static final int CONSENSUS_SATURATION = 3;
    /** 高可信阈值: 达到该分值方可驱动经验沉淀/badcase 导出. */
    public static final double HIGH_CREDIBILITY_THRESHOLD = 0.6;

    /**
     * 计算单条反馈的可信度分.
     *
     * @param feedback          待评分反馈
     * @param sameFingerprintCount 同指纹下反馈类型一致的反馈数 (含自身), 用于共识度
     * @return 可信度分 (0~1)
     */
    public double score(AlertFeedbackEntity feedback, int sameFingerprintCount) {
        double levelScore = normalizeLevel(feedback.getFeedbackerLevel());
        double consensusScore = normalizeConsensus(sameFingerprintCount);
        return W_LEVEL * levelScore + W_CONSENSUS * consensusScore;
    }

    /**
     * 判断反馈是否达到高可信门槛.
     *
     * @param feedback             待判断反馈
     * @param sameFingerprintCount 同指纹一致反馈数
     * @return 是否高可信
     */
    public boolean isHighCredibility(AlertFeedbackEntity feedback, int sameFingerprintCount) {
        return score(feedback, sameFingerprintCount) >= HIGH_CREDIBILITY_THRESHOLD;
    }

    private double normalizeLevel(Integer level) {
        if (level == null || level < 1) {
            return 1.0 / MAX_LEVEL;
        }
        int capped = Math.min(level, MAX_LEVEL);
        return (double) capped / MAX_LEVEL;
    }

    private double normalizeConsensus(int sameFingerprintCount) {
        int effective = Math.max(1, sameFingerprintCount);
        return Math.min(1.0, (double) effective / CONSENSUS_SATURATION);
    }

    /**
     * 统计给定反馈集合中与目标反馈 "同指纹 + 同类型" 的数量, 作为共识度输入.
     *
     * @param target 目标反馈
     * @param pool   反馈池
     * @return 同指纹同类型反馈数 (含自身)
     */
    public int consensusCount(AlertFeedbackEntity target, List<AlertFeedbackEntity> pool) {
        if (target.getFingerprint() == null) {
            return 1;
        }
        int count = 0;
        for (AlertFeedbackEntity item : pool) {
            if (target.getFingerprint().equals(item.getFingerprint())
                    && target.getFeedbackType() == item.getFeedbackType()) {
                count++;
            }
        }
        return Math.max(1, count);
    }
}
