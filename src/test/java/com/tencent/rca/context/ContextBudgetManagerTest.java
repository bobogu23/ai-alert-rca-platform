package com.tencent.rca.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencent.rca.common.enums.EvidenceSourceType;
import com.tencent.rca.domain.Evidence;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ContextBudgetManager 单元测试: 校验预算裁剪、空输入与截断摘要.
 */
class ContextBudgetManagerTest {

    private final ContextBudgetManager manager = new ContextBudgetManager();

    @Test
    void shouldReturnEmptyForNullOrEmptyOrZeroBudget() {
        assertThat(manager.prune(null, 1000)).isEmpty();
        assertThat(manager.prune(List.of(), 1000)).isEmpty();
        assertThat(manager.prune(List.of(evidence("d", "s", "snippet")), 0)).isEmpty();
    }

    @Test
    void shouldKeepAllWhenWithinBudget() {
        List<Evidence> candidates = List.of(
                evidence("异常出现 10 次", "ref1", "short"),
                evidence("异常出现 5 次", "ref2", "short"));
        List<Evidence> result = manager.prune(candidates, 10000);
        assertThat(result).hasSize(2);
        assertThat(result).noneMatch(e -> "budget-truncation".equals(e.sourceRef()));
    }

    @Test
    void shouldTruncateAndAppendSummaryWhenOverBudget() {
        List<Evidence> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candidates.add(evidence("证据 " + i, "ref" + i, "x".repeat(300)));
        }
        // 预算很小, 只能容纳少量证据, 其余应被截断并追加一条截断摘要
        List<Evidence> result = manager.prune(candidates, 200);
        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(e -> "budget-truncation".equals(e.sourceRef()));
        assertThat(result.size()).isLessThan(candidates.size() + 1);
    }

    private Evidence evidence(String description, String sourceRef, String snippet) {
        return new Evidence(description, EvidenceSourceType.LOG, sourceRef, snippet);
    }
}
