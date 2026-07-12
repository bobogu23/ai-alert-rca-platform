package com.tencent.rca.llm;

import com.tencent.rca.common.exception.RcaException;
import com.tencent.rca.config.RcaProperties;
import com.tencent.rca.fault.RetryExecutor;
import com.tencent.rca.fault.RetryableException;
import com.tencent.rca.fault.TimeLimitExceededException;
import com.tencent.rca.fault.TimeLimiter;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 大模型调用统一入口 (详细设计文档 4.5、7.2、7.3).
 * 基于 Spring AI ChatClient 对接 LiteLLM 网关, 按档位选择不同 model, 并对每次调用施加:
 * 1) 超时控制 (TimeLimiter), 超时优先, 不无限等待;
 * 2) 重试 (RetryExecutor), 网络抖动/5xx/限流/超时/结构化解析失败均视为可重试;
 * 每次重试都重新构建请求, 保证请求 spec 不被复用.
 */
@Slf4j
@Component
public class LlmClient {

    private static final double REASONING_TEMPERATURE = 0.2;
    private static final double LIGHTWEIGHT_TEMPERATURE = 0.3;

    private final ChatClient chatClient;
    private final RcaProperties properties;
    private final RetryExecutor retryExecutor;
    private final TimeLimiter timeLimiter;

    public LlmClient(ChatClient chatClient,
                     RcaProperties properties,
                     RetryExecutor retryExecutor,
                     TimeLimiter timeLimiter) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.retryExecutor = retryExecutor;
        this.timeLimiter = timeLimiter;
    }

    /**
     * 以纯文本形式调用大模型.
     *
     * @param tier         模型档位
     * @param systemPrompt 系统提示 (可为空)
     * @param userPrompt   用户提示
     * @return 模型返回的文本内容
     */
    public String complete(ModelTier tier, String systemPrompt, String userPrompt) {
        return callWithRetryAndTimeout("llm-complete-" + tier,
                () -> buildRequest(tier, systemPrompt, userPrompt).call().content());
    }

    /**
     * 以结构化对象形式调用大模型, 由 Spring AI 将响应解析为目标类型.
     * 解析失败会被视为可重试, 触发重新推理 (详细设计文档 4.2).
     *
     * @param tier         模型档位
     * @param systemPrompt 系统提示 (可为空)
     * @param userPrompt   用户提示
     * @param type         目标类型
     * @param <T>          目标类型泛型
     * @return 解析后的结构化对象
     */
    public <T> T completeStructured(ModelTier tier, String systemPrompt, String userPrompt, Class<T> type) {
        return callWithRetryAndTimeout("llm-structured-" + tier,
                () -> buildRequest(tier, systemPrompt, userPrompt).call().entity(type));
    }

    private ChatClient.ChatClientRequestSpec buildRequest(ModelTier tier, String systemPrompt, String userPrompt) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(resolveModel(tier))
                .temperature(resolveTemperature(tier))
                .build();
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt().options(options);
        if (StringUtils.hasText(systemPrompt)) {
            spec = spec.system(systemPrompt);
        }
        return spec.user(userPrompt);
    }

    private <T> T callWithRetryAndTimeout(String name, Supplier<T> llmCall) {
        return retryExecutor.execute(() -> {
            try {
                return timeLimiter.callWithTimeout(llmCall::get, properties.getTimeout().getLlmCallMs(), name);
            } catch (TimeLimitExceededException ex) {
                // 超时通常意味着模型/网关确实慢, 重试大概率再次超时并放大整体耗时;
                // 故不重试, 抛不可重试异常直接交由上层降级 (与堆栈裁剪等模块的超时处理原则一致)
                throw new RcaException("LLM 调用超时, 不重试直接降级: " + name, ex);
            } catch (RetryableException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                // 网络抖动/5xx/限流/结构化解析失败等瞬时故障视为可重试, 避免单次抖动导致整体失败
                throw new RetryableException("LLM 调用失败: " + name, ex);
            }
        }, properties.getLlm().getMaxRetries(), name);
    }

    private String resolveModel(ModelTier tier) {
        return tier == ModelTier.REASONING
                ? properties.getLlm().getReasoningModel()
                : properties.getLlm().getLightweightModel();
    }

    private double resolveTemperature(ModelTier tier) {
        return tier == ModelTier.REASONING ? REASONING_TEMPERATURE : LIGHTWEIGHT_TEMPERATURE;
    }
}
