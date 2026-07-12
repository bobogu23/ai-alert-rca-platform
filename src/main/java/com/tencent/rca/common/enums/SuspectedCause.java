package com.tencent.rca.common.enums;

/**
 * 疑似根因方向 (详细设计文档 6.3、迭代取证-证伪闭环).
 * 用于统一表达日志线索与待验证假设, 驱动定向取证. UNKNOWN 表示无法进一步定向.
 */
public enum SuspectedCause {
    /** Redis 超时/异常. */
    REDIS,
    /** 数据库慢查询/连接异常. */
    DB,
    /** 下游服务调用超时. */
    DOWNSTREAM,
    /** JVM GC 停顿. */
    GC,
    /** 网络异常(重传/丢包/连接失败). */
    NETWORK,
    /** 容器/主机资源(CPU/内存)异常. */
    RESOURCE,
    /** 无法进一步定向. */
    UNKNOWN;

    /**
     * 将日志 Agent 产出的 suspectedDependency 字符串映射为疑似根因方向.
     *
     * @param dependency 依赖类型字符串 (如 redis/db/downstream/resource/business/unknown)
     * @return 对应的疑似根因方向, 无法识别时返回 UNKNOWN
     */
    public static SuspectedCause fromDependency(String dependency) {
        if (dependency == null) {
            return UNKNOWN;
        }
        return switch (dependency.trim().toLowerCase()) {
            case "redis" -> REDIS;
            case "db" -> DB;
            case "downstream" -> DOWNSTREAM;
            case "resource" -> RESOURCE;
            default -> UNKNOWN;
        };
    }
}
