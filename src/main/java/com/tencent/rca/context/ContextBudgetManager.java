package com.tencent.rca.context;

import com.tencent.rca.common.enums.EvidenceSourceType;
import com.tencent.rca.domain.Evidence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 上下文 Token 预算控制器 (详细设计文档 5.5).
 * 组装 Prompt 前对候选证据按重要性评分降序裁剪, 累加 token 预估至预算上限即截断, 剩余低分证据仅保留统计摘要.
 * 纯本地确定性算法, 不依赖 LLM, 遵循"精度优先于覆盖度".
 */
@Component
public class ContextBudgetManager {

    /** 粗略 token 预估: 平均每 token 约 3 个字符. */
    private static final int CHARS_PER_TOKEN = 3;
    /** 截断摘要占位的 token 预估. */
    private static final int SUMMARY_TOKEN_COST = 20;

    /**
     * 按重要性评分裁剪候选证据至 token 预算内.
     *
     * @param candidates  候选证据列表
     * @param tokenBudget token 预算上限
     * @return 裁剪后的证据列表 (超预算时末尾追加一条截断摘要证据)
     */
    public List<Evidence> prune(List<Evidence> candidates, int tokenBudget) {
        List<Evidence> result = new ArrayList<>();
        if (candidates == null || candidates.isEmpty() || tokenBudget <= 0) {
            return result;
        }
        List<Evidence> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt(this::score).reversed());

        int usedTokens = 0;
        int droppedCount = 0;
        for (Evidence evidence : sorted) {
            int cost = estimateTokens(evidence);
            if (usedTokens + cost <= tokenBudget) {
                result.add(evidence);
                usedTokens += cost;
            } else {
                droppedCount++;
            }
        }
        if (droppedCount > 0) {
            result.add(buildTruncationSummary(droppedCount));
        }
        return result;
    }

    private int score(Evidence evidence) {
        int base = typeWeight(evidence.type());
        // 异常占比信息通常体现在描述中, 含"占比"/"次"的证据更具信息量, 适度加权
        String description = evidence.description() == null ? "" : evidence.description();
        int ratioBonus = description.contains("占比") || description.contains("次") ? 2 : 0;
        return base + ratioBonus;
    }

    private int typeWeight(EvidenceSourceType type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case LOG -> 5;
            case TRACE -> 4;
            case METRIC -> 3;
            case CODE -> 2;
            default -> 1;
        };
    }

    private int estimateTokens(Evidence evidence) {
        int length = 0;
        if (evidence.description() != null) {
            length += evidence.description().length();
        }
        if (evidence.rawSnippet() != null) {
            length += evidence.rawSnippet().length();
        }
        return Math.max(1, length / CHARS_PER_TOKEN);
    }

    private Evidence buildTruncationSummary(int droppedCount) {
        String description = String.format("因 token 预算限制, 另有 %d 条低重要性证据未展开", droppedCount);
        return new Evidence(description, EvidenceSourceType.LOG, "budget-truncation", "");
    }
}
