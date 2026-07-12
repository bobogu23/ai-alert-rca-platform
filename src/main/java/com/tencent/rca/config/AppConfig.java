package com.tencent.rca.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 应用级 Bean 配置.
 */
@Configuration
public class AppConfig {

    /**
     * 构建 Spring AI ChatClient, 底层 ChatModel 由 OpenAI starter 自动配置并指向 LiteLLM 网关.
     *
     * @param chatModel 自动配置的对话模型
     * @return ChatClient 实例
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 通用 RestClient 构建器, 供 MCP 客户端与通知渠道复用.
     *
     * @return RestClient.Builder 实例
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
