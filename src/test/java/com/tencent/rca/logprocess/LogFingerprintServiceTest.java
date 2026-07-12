package com.tencent.rca.logprocess;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencent.rca.domain.LogAnalysisResult.ExceptionCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LogFingerprintService 单元测试: 校验归一化与聚类去重.
 */
class LogFingerprintServiceTest {

    private final LogFingerprintService service = new LogFingerprintService();

    @Test
    void shouldReturnEmptyForNullOrEmpty() {
        assertThat(service.cluster(null)).isEmpty();
        assertThat(service.cluster(List.of())).isEmpty();
    }

    @Test
    void shouldNormalizeDynamicFields() {
        String normalized = service.normalize(
                "2026-07-09 23:13:01.123 order 1234567 from 10.0.0.1:8080 failed");
        assertThat(normalized).contains("<time>");
        assertThat(normalized).contains("<id>");
        assertThat(normalized).contains("<endpoint>");
    }

    @Test
    void shouldClusterSimilarLogsIntoOneCategory() {
        // 仅动态字段不同的同类异常应聚为一类
        List<String> logs = List.of(
                "NullPointerException at order 111111",
                "NullPointerException at order 222222",
                "NullPointerException at order 333333",
                "TimeoutException calling downstream");
        List<ExceptionCategory> categories = service.cluster(logs);

        assertThat(categories).hasSize(2);
        // 数量最多的类别排在最前
        assertThat(categories.get(0).count()).isEqualTo(3);
        assertThat(categories.get(0).ratio()).isEqualTo(0.75);
    }
}
