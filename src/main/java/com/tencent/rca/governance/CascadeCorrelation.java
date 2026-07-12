package com.tencent.rca.governance;

import com.tencent.rca.mcp.GalileoMcpClient;
import com.tencent.rca.repository.entity.AlertCaseEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 级联根因合并 (详细设计文档 6.2).
 * 基于调用链拓扑将时间窗内的同源故障合并为一个根因案卷: 构建有向调用图 -> 找到最下游被依赖的收敛点作为根因服务
 * -> 仅根因服务案卷保留, 其余置为级联受影响. 调用链数据缺失时安全降级为"不合并".
 */
@Slf4j
@Service
public class CascadeCorrelation {

    private final GalileoMcpClient galileoMcpClient;

    public CascadeCorrelation(GalileoMcpClient galileoMcpClient) {
        this.galileoMcpClient = galileoMcpClient;
    }

    /**
     * 对时间窗内的活跃案卷做级联根因合并.
     *
     * @param activeCasesInWindow 时间窗内的活跃案卷
     * @return 级联合并结果 (无法合并时 rootCaseId 为空)
     */
    public CascadeResult correlate(List<AlertCaseEntity> activeCasesInWindow) {
        if (activeCasesInWindow == null || activeCasesInWindow.size() < 2) {
            return new CascadeResult(null, List.of());
        }
        Map<String, AlertCaseEntity> serviceToCase = indexByService(activeCasesInWindow);
        Set<String> activeServices = serviceToCase.keySet();

        Map<String, Set<String>> edges = buildCallGraph(activeServices);
        if (edges.isEmpty()) {
            log.info("调用链数据缺失, 级联合并降级为不合并");
            return new CascadeResult(null, List.of());
        }

        String rootService = findRootService(activeServices, edges);
        if (rootService == null || !serviceToCase.containsKey(rootService)) {
            return new CascadeResult(null, List.of());
        }

        Long rootCaseId = serviceToCase.get(rootService).getId();
        List<Long> cascaded = new ArrayList<>();
        for (Map.Entry<String, AlertCaseEntity> entry : serviceToCase.entrySet()) {
            if (!entry.getKey().equals(rootService)) {
                cascaded.add(entry.getValue().getId());
            }
        }
        return new CascadeResult(rootCaseId, cascaded);
    }

    private Map<String, AlertCaseEntity> indexByService(List<AlertCaseEntity> cases) {
        Map<String, AlertCaseEntity> map = new HashMap<>();
        for (AlertCaseEntity entity : cases) {
            // 同服务多案卷时保留最早的一个作为代表
            map.putIfAbsent(entity.getServiceName(), entity);
        }
        return map;
    }

    private Map<String, Set<String>> buildCallGraph(Set<String> activeServices) {
        Map<String, Set<String>> edges = new HashMap<>();
        for (String service : activeServices) {
            try {
                Map<String, Object> deps = galileoMcpClient.fetchCallDependencies(service);
                extractEdges(deps, activeServices, edges);
            } catch (RuntimeException ex) {
                log.warn("拉取服务 {} 调用关系失败, 忽略该服务: {}", service, ex.getMessage());
            }
        }
        return edges;
    }

    @SuppressWarnings("unchecked")
    private void extractEdges(Map<String, Object> deps, Set<String> activeServices, Map<String, Set<String>> edges) {
        if (deps == null) {
            return;
        }
        Object list = deps.get("dependencies");
        if (!(list instanceof List<?> items)) {
            return;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> edge)) {
                continue;
            }
            Object caller = edge.get("caller_server");
            Object callee = edge.get("callee_server");
            if (caller == null || callee == null) {
                continue;
            }
            String callerName = String.valueOf(caller);
            String calleeName = String.valueOf(callee);
            // 仅保留告警活跃服务之间的边, 聚焦本次级联范围
            if (activeServices.contains(callerName) && activeServices.contains(calleeName)) {
                edges.computeIfAbsent(callerName, key -> new HashSet<>()).add(calleeName);
            }
        }
    }

    private String findRootService(Set<String> activeServices, Map<String, Set<String>> edges) {
        // 根因服务为最下游被依赖者: 被其他活跃服务调用(有入边), 且自身不再调用其他活跃服务(无出边)
        Set<String> hasOutEdge = edges.keySet();
        Set<String> isCallee = new HashSet<>();
        for (Set<String> callees : edges.values()) {
            isCallee.addAll(callees);
        }
        String root = null;
        for (String service : activeServices) {
            if (isCallee.contains(service) && !hasOutEdge.contains(service)) {
                if (root != null) {
                    // 存在多个候选收敛点, 无法唯一确定根因, 安全降级
                    return null;
                }
                root = service;
            }
        }
        return root;
    }
}
