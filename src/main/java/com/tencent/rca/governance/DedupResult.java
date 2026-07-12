package com.tencent.rca.governance;

/**
 * 去重判定结果 (详细设计文档 6.1).
 *
 * @param isNew  是否为新告警(需触发分析)
 * @param caseId 归属案卷 ID(新建或既有)
 */
public record DedupResult(
        boolean isNew,
        Long caseId) {
}
