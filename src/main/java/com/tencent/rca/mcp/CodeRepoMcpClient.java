package com.tencent.rca.mcp;

import com.tencent.rca.config.McpProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 代码仓库 MCP 客户端. 提供代码检索、最近发布查询与 blame 能力,
 * 供 CodeContextAgent 结合日志/Trace 线索定位业务代码与配置隐患 (详细设计文档 2.1).
 */
@Slf4j
@Component
public class CodeRepoMcpClient {

    private final McpClient mcpClient;
    private final McpProperties properties;

    public CodeRepoMcpClient(McpClient mcpClient, McpProperties properties) {
        this.mcpClient = mcpClient;
        this.properties = properties;
    }

    /**
     * 按关键字 (类名/方法名/SQL/Redis key 前缀等) 检索代码片段.
     *
     * @param keyword 检索关键字
     * @param limit   返回片段条数上限
     * @return 代码片段文本列表
     */
    public List<String> searchCode(String keyword, int limit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", keyword);
        args.put("limit", limit);
        Map<String, Object> result = callTool("search_code", args);
        return extractStringList(result, "snippets");
    }

    /**
     * 查询服务最近的发布/变更记录, 用于关联"故障是否由最近变更引入".
     *
     * @param serviceName 服务名
     * @return 最近发布记录文本列表
     */
    public List<String> fetchRecentReleases(String serviceName) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("service", serviceName);
        Map<String, Object> result = callTool("recent_releases", args);
        return extractStringList(result, "releases");
    }

    /**
     * 对指定文件的指定行区间做 blame, 定位最近改动的提交与作者.
     *
     * @param filePath  文件路径
     * @param startLine 起始行
     * @param endLine   结束行
     * @return blame 结果文本列表
     */
    public List<String> blame(String filePath, int startLine, int endLine) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("file", filePath);
        args.put("start_line", startLine);
        args.put("end_line", endLine);
        Map<String, Object> result = callTool("git_blame", args);
        return extractStringList(result, "blame");
    }

    private Map<String, Object> callTool(String toolName, Map<String, Object> args) {
        return mcpClient.callTool(
                properties.getCoderepo().getBaseUrl(), properties.getCoderepo().getToken(), toolName, args);
    }

    private List<String> extractStringList(Map<String, Object> result, String key) {
        List<String> items = new ArrayList<>();
        Object value = result.get(key);
        if (value instanceof List<?> list) {
            for (Object item : list) {
                items.add(String.valueOf(item));
            }
        }
        log.debug("代码仓库 MCP 字段 {} 返回 {} 条", key, items.size());
        return items;
    }
}
