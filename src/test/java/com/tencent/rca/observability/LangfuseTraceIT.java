package com.tencent.rca.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencent.rca.llm.LlmClient;
import com.tencent.rca.llm.ModelTier;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Langfuse Trace 上报集成测试.
 * 验证一次真实 LLM 调用能生成 gen_ai span 并经 OpenTelemetry OTLP 导出器上报到 Langfuse.
 *
 * 默认跳过. 满足以下条件后可直接运行验证 (无需改代码):
 * 1) 设置 LANGFUSE_IT=true 启用本测试;
 * 2) 设置 LANGFUSE_ENABLED=true, 并填入 LANGFUSE_HOST / LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY;
 * 3) 提供可用的 LiteLLM 网关 (LITELLM_BASE_URL / LITELLM_API_KEY / LITELLM_DEFAULT_MODEL).
 *
 * 运行示例:
 *   LANGFUSE_IT=true LANGFUSE_ENABLED=true \
 *   LANGFUSE_HOST=https://cloud.langfuse.com \
 *   LANGFUSE_PUBLIC_KEY=pk-lf-xxx LANGFUSE_SECRET_KEY=sk-lf-xxx \
 *   LITELLM_BASE_URL=http://127.0.0.1:4000 LITELLM_API_KEY=xxx LITELLM_DEFAULT_MODEL=glm-5.2 \
 *   mvn -Dtest=LangfuseTraceIT test
 * 运行后到 Langfuse 控制台 Traces 页面即可看到本次 LLM 交互的 Trace(含 prompt/completion/token).
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LANGFUSE_IT", matches = "true")
class LangfuseTraceIT {

    private static final long FLUSH_TIMEOUT_SECONDS = 30L;

    @Autowired
    private LlmClient llmClient;

    /** OpenTelemetry 实例, 由 Micrometer Tracing OTel 桥接自动配置; 用于强制 flush 确保 Trace 上报. */
    @Autowired
    private OpenTelemetry openTelemetry;

    @Test
    void shouldExportLlmInteractionTraceToLangfuse() {
        // 1) 触发一次真实 LLM 调用, Spring AI 会为其生成 gen_ai chat span
        String content = llmClient.complete(
                ModelTier.LIGHTWEIGHT,
                "你是测试助手, 请只回复一个词.",
                "请回复: langfuse-trace-ok");
        System.out.println("LLM answered: " + content);
        assertThat(content).isNotBlank();

        // 2) 断言追踪已装配为 OTel SDK (而非 no-op), 否则说明依赖/配置未生效
        assertThat(openTelemetry).isInstanceOf(OpenTelemetrySdk.class);

        // 3) 强制 flush, 确保 span 立即通过 OTLP 导出器推送到 Langfuse (不等待批处理周期)
        OpenTelemetrySdk sdk = (OpenTelemetrySdk) openTelemetry;
        CompletableResultCode result = sdk.getSdkTracerProvider()
                .forceFlush()
                .join(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(result.isSuccess()).isTrue();
        System.out.println("Trace 已上报, 请到 Langfuse 控制台 Traces 页面查看本次 LLM 交互.");
    }
}
