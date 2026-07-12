package com.tencent.rca.mcp;

import com.tencent.rca.config.McpProperties;
import com.tencent.rca.domain.AlertContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CLS 日志 MCP 客户端. 优先聚合查询, 仅拉取代表性样例, 避免全量日志灌入 (详细设计文档 5.1).
 */
@Slf4j
@Component
public class ClsMcpClient {

    private final McpClient mcpClient;
    private final McpProperties properties;

    public ClsMcpClient(McpClient mcpClient, McpProperties properties) {
        this.mcpClient = mcpClient;
        this.properties = properties;
    }

    /**
     * 拉取告警时间窗口内的异常日志样例.
     *
     * @param context     告警上下文
     * @param sampleLimit 样例条数上限
     * @return 原始日志文本列表
     */
    @SuppressWarnings("unchecked")
    public List<String> fetchErrorLogSamples(AlertContext context, int sampleLimit) {
        Map<String, Object> args = Map.of(
                "target", context.serviceName(),
                "namespace", "prod".equalsIgnoreCase(context.env()) ? "Production" : "Development",
                "filters", "level = error",
                "start_time", context.timeWindow().start().toString(),
                "end_time", context.timeWindow().end().toString(),
                "return_log_samples_num", String.valueOf(sampleLimit));
        Map<String, Object> result = mcpClient.callTool(
                properties.getCls().getBaseUrl(), properties.getCls().getToken(), "get_log_data", args);
        Object logs = result.get("sample_logs");
        List<String> samples = new ArrayList<>();
        if (logs instanceof List<?> list) {
            for (Object item : list) {
                samples.add(String.valueOf(item));
            }
        }
        log.debug("CLS 返回 {} 服务日志样例 {} 条", context.serviceName(), samples.size());
        return samples;
    }
}
