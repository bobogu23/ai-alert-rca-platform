package com.tencent.rca.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse 可观测性接入配置, 对应 application.yml 中的 rca.langfuse.* 节点.
 * 通过 OpenTelemetry OTLP 协议把 Spring AI 的 LLM 交互 Trace 上报到 Langfuse.
 * 密钥与地址仅预留配置项占位, 实际值通过环境变量注入, 不硬编码入库.
 */
@Data
@ConfigurationProperties(prefix = "rca.langfuse")
public class LangfuseProperties {

    /** 是否启用 Langfuse Trace 上报. 默认关闭, 填入密钥并置 true 后生效. */
    private boolean enabled = false;

    /**
     * Langfuse 主机地址 (不含路径), 按数据区域选择, 例如:
     * EU: https://cloud.langfuse.com; US: https://us.cloud.langfuse.com; 自托管: http://localhost:3000.
     * OTLP Trace 上报路径 /api/public/otel/v1/traces 由程序自动拼接.
     */
    private String host;

    /** Langfuse Public Key (形如 pk-lf-...), 用于 OTLP Basic Auth 用户名. */
    private String publicKey;

    /** Langfuse Secret Key (形如 sk-lf-...), 用于 OTLP Basic Auth 密码. */
    private String secretKey;
}
