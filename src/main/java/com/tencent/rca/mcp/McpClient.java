package com.tencent.rca.mcp;

import com.tencent.rca.fault.RetryableException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 通用 MCP HTTP 客户端封装. 将网络抖动与 5xx/429 映射为可重试异常 (详细设计文档 7.3),
 * 由上层 RetryExecutor + TimeLimiter 统一施加重试与超时策略.
 * 真实接入不同 MCP 协议时只需替换本实现, 不影响 Agent.
 */
@Slf4j
@Component
public class McpClient {

    private final RestClient restClient;

    public McpClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(15000);
        this.restClient = builder.requestFactory(factory).build();
    }

    /**
     * 调用某个 MCP 工具.
     *
     * @param baseUrl  MCP Server 基础地址
     * @param token    访问令牌
     * @param toolName 工具名
     * @param args     工具参数
     * @return 工具返回的 JSON 结构 (Map)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callTool(String baseUrl, String token, String toolName, Map<String, Object> args) {
        String url = baseUrl + "/tools/" + toolName;
        try {
            return restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (StringUtils.hasText(token)) {
                            headers.add("X-API-Token", token);
                        }
                    })
                    .body(args)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new RetryableException("MCP 服务端错误: " + response.getStatusCode());
                    })
                    .onStatus(status -> status.value() == 429, (request, response) -> {
                        throw new RetryableException("MCP 触发限流(429)");
                    })
                    .body(Map.class);
        } catch (ResourceAccessException ex) {
            throw new RetryableException("MCP 网络访问异常: " + toolName, ex);
        }
    }
}
