package com.tencent.rca.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Spring AI 对话内容观测过滤器 (Langfuse 接入必需).
 * Spring AI 的 log-prompt/log-completion 只在观测上下文内收集内容, 不会自动写入 OTel span 属性,
 * 缺少本过滤器时 Langfuse 里的 span 输入/输出会是 null. 本过滤器把 prompt 与 completion 分别写入
 * gen_ai.prompt 与 gen_ai.completion 两个高基数属性, 使其随 span 上报到 Langfuse.
 * 仅当 rca.langfuse.enabled=true 时注入, 避免默认场景引入内容采集.
 */
@Component
@ConditionalOnProperty(prefix = "rca.langfuse", name = "enabled", havingValue = "true")
public class ChatModelCompletionContentObservationFilter implements ObservationFilter {

    private static final String PROMPT_KEY = "gen_ai.prompt";
    private static final String COMPLETION_KEY = "gen_ai.completion";

    @Override
    public Observation.Context map(Observation.Context context) {
        if (!(context instanceof ChatModelObservationContext chatContext)) {
            return context;
        }
        String prompt = extractPrompt(chatContext.getRequest());
        if (StringUtils.hasText(prompt)) {
            context.addHighCardinalityKeyValue(KeyValue.of(PROMPT_KEY, prompt));
        }
        String completion = extractCompletion(chatContext.getResponse());
        if (StringUtils.hasText(completion)) {
            context.addHighCardinalityKeyValue(KeyValue.of(COMPLETION_KEY, completion));
        }
        return context;
    }

    private String extractPrompt(Prompt prompt) {
        return prompt == null ? null : prompt.getContents();
    }

    private String extractCompletion(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return null;
        }
        Generation generation = response.getResult();
        return generation.getOutput() == null ? null : generation.getOutput().getText();
    }
}
