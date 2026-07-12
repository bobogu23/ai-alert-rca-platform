package com.tencent.rca.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 数据源接入配置, 对应 application.yml 中的 rca.mcp.* 节点.
 */
@Data
@ConfigurationProperties(prefix = "rca.mcp")
public class McpProperties {

    /** CLS 日志 MCP. */
    private Endpoint cls = new Endpoint();
    /** 伽利略 MCP. */
    private Endpoint galileo = new Endpoint();
    /** 代码仓库 MCP. */
    private Endpoint coderepo = new Endpoint();

    /** MCP 服务端点. */
    @Data
    public static class Endpoint {
        /** MCP Server 基础地址. */
        private String baseUrl;
        /** 访问令牌. */
        private String token;
    }
}
