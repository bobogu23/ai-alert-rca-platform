package com.tencent.rca.config;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Langfuse Trace 上报配置 (基于 OpenTelemetry OTLP).
 * 仅当 rca.langfuse.enabled=true 时生效: 依据 host + public/secret key 构造指向 Langfuse OTLP 端点、
 * 带 HTTP Basic Auth 的 span 导出器. Micrometer Tracing 会把 Spring AI 的 LLM 观测转为 OTel span,
 * 经本导出器上报到 Langfuse. 未配置密钥或未启用时不注入该 Bean, 不影响应用正常运行.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(LangfuseProperties.class)
@ConditionalOnProperty(prefix = "rca.langfuse", name = "enabled", havingValue = "true")
public class LangfuseTracingConfig {

    /** Langfuse OTLP Trace 摄取路径, 拼接在 host 之后. */
    private static final String OTLP_TRACES_PATH = "/api/public/otel/v1/traces";
    /** Langfuse 摄取版本头, 遵循官方接入说明. */
    private static final String INGESTION_VERSION_HEADER = "x-langfuse-ingestion-version";
    private static final String INGESTION_VERSION_VALUE = "4";

    /**
     * 构造指向 Langfuse 的 OTLP HTTP span 导出器.
     * Micrometer Tracing 的 OTel 自动配置会自动接入该导出器, 从而将 Trace 上报到 Langfuse.
     *
     * @param properties Langfuse 配置
     * @return OTLP span 导出器
     */
    @Bean
    public OtlpHttpSpanExporter langfuseOtlpHttpSpanExporter(LangfuseProperties properties) {
        Assert.isTrue(StringUtils.hasText(properties.getHost()), "rca.langfuse.host 未配置");
        Assert.isTrue(StringUtils.hasText(properties.getPublicKey()), "rca.langfuse.public-key 未配置");
        Assert.isTrue(StringUtils.hasText(properties.getSecretKey()), "rca.langfuse.secret-key 未配置");

        String endpoint = normalizeHost(properties.getHost()) + OTLP_TRACES_PATH;
        String basicAuth = buildBasicAuth(properties.getPublicKey(), properties.getSecretKey());
        log.info("Langfuse Trace 上报已启用, endpoint={}", endpoint);
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("Authorization", "Basic " + basicAuth)
                .addHeader(INGESTION_VERSION_HEADER, INGESTION_VERSION_VALUE)
                .build();
    }

    private String normalizeHost(String host) {
        String trimmed = host.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String buildBasicAuth(String publicKey, String secretKey) {
        String raw = publicKey + ":" + secretKey;
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
