package com.tencent.rca.common.enums;

/**
 * 告警类型, 决定归因分析的路由分支 (详细设计文档 6.3).
 */
public enum AlertType {
    /** CLS 异常日志阈值告警. */
    LOG_THRESHOLD,
    /** 接口耗时高或上游调用超时. */
    LATENCY,
    /** JVM 或容器资源异常. */
    RESOURCE,
    /** 未能识别的其他类型. */
    OTHER
}
