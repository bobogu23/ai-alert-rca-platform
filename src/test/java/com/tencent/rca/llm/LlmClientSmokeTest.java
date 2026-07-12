package com.tencent.rca.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * LlmClient 真实调用冒烟测试: 验证 LiteLLM 网关连通性与结构化输出解析.
 * 需真实网关与凭据, 默认跳过; 设置环境变量 RCA_LLM_IT=true 后运行.
 * 断言刻意宽松, 只校验"可连通、有返回、可解析", 不校验语义, 避免模型输出波动导致 flaky.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RCA_LLM_IT", matches = "true")
class LlmClientSmokeTest {

    @Autowired
    private LlmClient llmClient;

    @Test
    void shouldReturnNonBlankTextForPlainCompletion() {
        String content = llmClient.complete(
                ModelTier.LIGHTWEIGHT,
                "你是测试助手, 请只回复一个词.",
                "请回复: okkk");
        System.out.println(content);
        assertThat(content).isNotBlank();
    }

    @Test
    void shouldParseStructuredOutput() {
        EchoOutput output = llmClient.completeStructured(
                ModelTier.LIGHTWEIGHT,
                "你是测试助手. 请严格输出 JSON, 字段 message 为字符串, ok 为布尔值.",
                "请返回 message=hello, ok=true",
                EchoOutput.class);

        assertThat(output).isNotNull();
        assertThat(output.message()).isNotNull();
    }

    /**
     * 结构化解析的最简目标类型.
     *
     * @param message 任意文本消息
     * @param ok      任意布尔标记
     */
    record EchoOutput(String message, Boolean ok) {
    }
}
