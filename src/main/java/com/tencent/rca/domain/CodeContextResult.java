package com.tencent.rca.domain;

import java.util.List;

/**
 * 代码上下文 Agent 的结构化输出.
 *
 * @param codeSnippets  相关代码片段
 * @param recentChanges 最近相关发布/变更记录
 * @param riskPoints    配置隐患点 (超时/重试/连接池/缓存等)
 * @param evidences     产出的证据列表
 * @param degraded      是否因数据源失败而降级
 * @param degradeNote   降级说明
 */
public record CodeContextResult(
        List<String> codeSnippets,
        List<String> recentChanges,
        List<String> riskPoints,
        List<Evidence> evidences,
        boolean degraded,
        String degradeNote) {
}
