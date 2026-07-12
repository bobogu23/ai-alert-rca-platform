package com.tencent.rca.mcp;

import com.tencent.rca.config.McpProperties;
import com.tencent.rca.domain.AlertContext;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 伽利略 MCP 客户端. 提供 Metric、Trace 查询与服务调用关系拓扑查询能力.
 */
@Slf4j
@Component
public class GalileoMcpClient {

    private final McpClient mcpClient;
    private final McpProperties properties;

    public GalileoMcpClient(McpClient mcpClient, McpProperties properties) {
        this.mcpClient = mcpClient;
        this.properties = properties;
    }

    /**
     * 查询服务关键指标 (QPS、错误率、P99 等).
     *
     * @param context 告警上下文
     * @return 指标数据 JSON
     */
    public Map<String, Object> fetchMetrics(AlertContext context) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("target", context.serviceName());
        args.put("metric_name", "rpc.server.duration");
        args.put("start_time", context.timeWindow().start().toString());
        args.put("end_time", context.timeWindow().end().toString());
        return mcpClient.callTool(
                properties.getGalileo().getBaseUrl(), properties.getGalileo().getToken(), "get_metric_data", args);
    }

    /**
     * 抽样慢 Trace, 聚合 Top 慢 span.
     *
     * @param context 告警上下文
     * @return Trace 数据 JSON
     */
    public Map<String, Object> fetchSlowTraces(AlertContext context) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("target", context.serviceName());
        args.put("start_time", context.timeWindow().start().toString());
        args.put("end_time", context.timeWindow().end().toString());
        args.put("group_by_tags", java.util.List.of("callee_server", "callee_method"));
        return mcpClient.callTool(
                properties.getGalileo().getBaseUrl(), properties.getGalileo().getToken(), "get_trace_data", args);
    }

    /**
     * 按指定指标名定向查询服务指标, 用于对某个疑似根因做证实/证伪取证 (迭代取证闭环).
     *
     * @param context    告警上下文
     * @param metricName 指标名 (如 redis 客户端耗时、JVM GC 停顿、网络重传率等)
     * @return 指标数据 JSON
     */
    public Map<String, Object> fetchMetricByName(AlertContext context, String metricName) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("target", context.serviceName());
        args.put("metric_name", metricName);
        args.put("start_time", context.timeWindow().start().toString());
        args.put("end_time", context.timeWindow().end().toString());
        return mcpClient.callTool(
                properties.getGalileo().getBaseUrl(), properties.getGalileo().getToken(), "get_metric_data", args);
    }

    /**
     * 查询服务调用关系, 用于级联告警因果合并 (详细设计文档 6.2).
     *
     * @param serviceName 服务名
     * @return 调用关系数据 JSON
     */
    public Map<String, Object> fetchCallDependencies(String serviceName) {
        Map<String, Object> args = Map.of("search_name", serviceName);
        return mcpClient.callTool(
                properties.getGalileo().getBaseUrl(), properties.getGalileo().getToken(), "search_targets", args);
    }
}
