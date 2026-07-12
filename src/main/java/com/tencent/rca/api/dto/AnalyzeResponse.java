package com.tencent.rca.api.dto;

/**
 * 告警分析响应 (详细设计文档 2.2).
 *
 * @param caseId 案卷 ID (被抑制时可能为空)
 * @param status 受理状态 (ANALYZING/MERGED/SUPPRESSED)
 */
public record AnalyzeResponse(
        String caseId,
        String status) {
}
