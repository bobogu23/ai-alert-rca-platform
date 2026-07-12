package com.tencent.rca.common.enums;

/**
 * 告警来源.
 */
public enum AlertSource {
    /** 腾讯云 CLS 日志异常告警. */
    CLS,
    /** 伽利略监控告警. */
    GALILEO,
    /** Prometheus Alertmanager 告警. */
    PROMETHEUS
}
