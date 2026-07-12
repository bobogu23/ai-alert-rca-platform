package com.tencent.rca.api.dto;

/**
 * 案卷视图, 用于查询接口返回 (详细设计文档 2.2).
 *
 * @param caseId           案卷 ID
 * @param serviceName      服务名
 * @param status           案卷状态
 * @param conclusion       根因结论摘要
 * @param confidence       置信度
 * @param degradeNote      降级说明
 * @param reportUrl        报告地址
 * @param mergedAlertCount 归并告警数量
 */
public record CaseView(
        Long caseId,
        String serviceName,
        String status,
        String conclusion,
        String confidence,
        String degradeNote,
        String reportUrl,
        Integer mergedAlertCount) {
}
