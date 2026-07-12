package com.tencent.rca.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 系统自监控指标 (详细设计文档 8.1、7.8).
 * 基于 Micrometer 埋点, 指标统一以 rca_ 为前缀. 埋点不侵入业务异常流, 仅在关键节点被显式调用.
 */
@Component
public class RcaMetrics {

    private static final String AGENT_DEGRADED = "rca_agent_degraded_total";
    private static final String CRITIC_REJECT = "rca_critic_reject_total";
    private static final String FALLBACK = "rca_fallback_total";
    private static final String NOTIFY_FAIL = "rca_notification_fail_total";
    private static final String ANALYZE_COMPLETED = "rca_analyze_completed_total";
    private static final String ANALYZE_REJECTED = "rca_analyze_rejected_total";
    private static final String ANALYZE_DURATION = "rca_analyze_duration";

    private final MeterRegistry registry;

    public RcaMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录某证据收集 Agent 发生降级.
     *
     * @param agent Agent 名称
     */
    public void recordAgentDegraded(String agent) {
        registry.counter(AGENT_DEGRADED, "agent", agent).increment();
    }

    /**
     * 记录一次 Critic 打回.
     */
    public void recordCriticReject() {
        registry.counter(CRITIC_REJECT).increment();
    }

    /**
     * 记录一次兜底报告触发.
     */
    public void recordFallback() {
        registry.counter(FALLBACK).increment();
    }

    /**
     * 记录一次渠道推送失败.
     *
     * @param channel 渠道名称
     */
    public void recordNotificationFailure(String channel) {
        registry.counter(NOTIFY_FAIL, "channel", channel).increment();
    }

    /**
     * 记录一次分析完成及其最终状态.
     *
     * @param status 最终状态 (如 ANALYZED/ANALYZE_FAILED)
     */
    public void recordAnalyzeCompleted(String status) {
        registry.counter(ANALYZE_COMPLETED, "status", status).increment();
    }

    /**
     * 记录一次因并发限流被拒绝的分析.
     */
    public void recordAnalyzeRejected() {
        registry.counter(ANALYZE_REJECTED).increment();
    }

    /**
     * 记录一次分析整体耗时.
     *
     * @param millis 耗时毫秒
     */
    public void recordAnalyzeDuration(long millis) {
        registry.timer(ANALYZE_DURATION).record(Duration.ofMillis(millis));
    }
}
