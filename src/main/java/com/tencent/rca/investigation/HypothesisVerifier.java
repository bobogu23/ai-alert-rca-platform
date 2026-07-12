package com.tencent.rca.investigation;

import com.tencent.rca.common.enums.EvidenceSourceType;
import com.tencent.rca.common.enums.SuspectedCause;
import com.tencent.rca.config.RcaProperties;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.Evidence;
import com.tencent.rca.fault.RetryExecutor;
import com.tencent.rca.fault.TimeLimiter;
import com.tencent.rca.mcp.GalileoMcpClient;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 假设验证器 (迭代取证-证伪闭环).
 * 针对某个疑似根因方向, 定向拉取对应监控指标并用本地确定性阈值判定是否成立(证实/证伪).
 * 数据缺失或查询失败时返回"不可判定"(conclusive=false), 安全降级, 不臆断.
 * 防幻觉: 判定完全由代码基于真实指标完成, 不经 LLM; 每条产出证据携带可追溯 sourceRef.
 * 容错: MCP 调用经 RetryExecutor + TimeLimiter 封装, 失败即降级为不可判定.
 */
@Slf4j
@Component
public class HypothesisVerifier {

    /** Redis 客户端调用耗时指标(毫秒), 超过阈值视为 Redis 慢/超时. */
    private static final String METRIC_REDIS = "redis.client.duration";
    /** DB 查询耗时指标(毫秒). */
    private static final String METRIC_DB = "db.query.duration";
    /** 下游调用耗时指标(毫秒). */
    private static final String METRIC_DOWNSTREAM = "rpc.client.duration";
    /** JVM GC 停顿累计时长指标(毫秒). */
    private static final String METRIC_GC = "jvm.gc.pause";
    /** 网络错误率指标(0~1). */
    private static final String METRIC_NETWORK = "net.error.rate";
    /** 容器 CPU 使用率指标(0~1). */
    private static final String METRIC_RESOURCE = "container.cpu.usage";

    private static final double THRESHOLD_LATENCY_MS = 200.0;
    private static final double THRESHOLD_GC_PAUSE_MS = 500.0;
    private static final double THRESHOLD_ERROR_RATE = 0.05;
    private static final double THRESHOLD_CPU_USAGE = 0.85;

    private final GalileoMcpClient galileoMcpClient;
    private final RetryExecutor retryExecutor;
    private final TimeLimiter timeLimiter;
    private final RcaProperties properties;

    public HypothesisVerifier(GalileoMcpClient galileoMcpClient,
                              RetryExecutor retryExecutor,
                              TimeLimiter timeLimiter,
                              RcaProperties properties) {
        this.galileoMcpClient = galileoMcpClient;
        this.retryExecutor = retryExecutor;
        this.timeLimiter = timeLimiter;
        this.properties = properties;
    }

    /**
     * 验证某个疑似根因方向是否成立.
     *
     * @param context 告警上下文
     * @param cause   待验证的疑似根因方向
     * @return 验证结果(证实/证伪/不可判定)
     */
    public VerificationResult verify(AlertContext context, SuspectedCause cause) {
        String metricName = resolveMetric(cause);
        if (metricName == null) {
            return inconclusive(cause, "无对应可验证指标");
        }

        Map<String, Object> metricData;
        try {
            metricData = fetchWithFaultTolerance(context, metricName);
        } catch (RuntimeException ex) {
            log.warn("[HypothesisVerifier] 验证 {} 时指标查询失败, 判定不可判定: {}", cause, ex.getMessage());
            return inconclusive(cause, "指标查询失败: " + ex.getMessage());
        }

        Optional<Double> value = extractNumeric(metricData);
        if (value.isEmpty()) {
            return inconclusive(cause, "指标数据缺失或无法解析");
        }

        double observed = value.get();
        double threshold = resolveThreshold(cause);
        boolean confirmed = observed > threshold;
        String summary = String.format("%s: 观测值 %.2f, 阈值 %.2f -> %s",
                metricName, observed, threshold, confirmed ? "成立" : "不成立");
        Evidence evidence = new Evidence(
                "假设验证[" + cause + "] " + summary,
                EvidenceSourceType.METRIC,
                metricName + "@" + context.serviceName(),
                truncate(String.valueOf(metricData)));
        log.info("[HypothesisVerifier] 输出: cause={}, confirmed={}, {}", cause, confirmed, summary);
        return new VerificationResult(cause, confirmed, true, summary, Optional.of(evidence));
    }

    private Map<String, Object> fetchWithFaultTolerance(AlertContext context, String metricName) {
        return retryExecutor.execute(
                () -> timeLimiter.callWithTimeout(
                        () -> galileoMcpClient.fetchMetricByName(context, metricName),
                        properties.getTimeout().getMcpCallMs(),
                        "verify-" + metricName),
                properties.getLlm().getMaxRetries(),
                "verify-" + metricName);
    }

    private String resolveMetric(SuspectedCause cause) {
        return switch (cause) {
            case REDIS -> METRIC_REDIS;
            case DB -> METRIC_DB;
            case DOWNSTREAM -> METRIC_DOWNSTREAM;
            case GC -> METRIC_GC;
            case NETWORK -> METRIC_NETWORK;
            case RESOURCE -> METRIC_RESOURCE;
            default -> null;
        };
    }

    private double resolveThreshold(SuspectedCause cause) {
        return switch (cause) {
            case GC -> THRESHOLD_GC_PAUSE_MS;
            case NETWORK -> THRESHOLD_ERROR_RATE;
            case RESOURCE -> THRESHOLD_CPU_USAGE;
            default -> THRESHOLD_LATENCY_MS;
        };
    }

    @SuppressWarnings("unchecked")
    private Optional<Double> extractNumeric(Map<String, Object> metricData) {
        if (metricData == null || metricData.isEmpty()) {
            return Optional.empty();
        }
        // 兼容常见指标返回字段: value/current/avg/p99/max
        for (String key : new String[] {"value", "current", "avg", "p99", "max"}) {
            Object raw = metricData.get(key);
            Optional<Double> parsed = toDouble(raw);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        // 兼容嵌套 data 结构
        Object data = metricData.get("data");
        if (data instanceof Map<?, ?> nested) {
            return extractNumeric((Map<String, Object>) nested);
        }
        return Optional.empty();
    }

    private Optional<Double> toDouble(Object raw) {
        if (raw instanceof Number number) {
            return Optional.of(number.doubleValue());
        }
        if (raw instanceof String str) {
            try {
                return Optional.of(Double.parseDouble(str.trim()));
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private VerificationResult inconclusive(SuspectedCause cause, String reason) {
        log.info("[HypothesisVerifier] 输出: cause={}, 不可判定({})", cause, reason);
        return new VerificationResult(cause, false, false, reason, Optional.empty());
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        int maxLen = 600;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
