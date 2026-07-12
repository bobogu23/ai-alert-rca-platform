package com.tencent.rca.api.dto;

import com.tencent.rca.common.enums.AlertSource;
import com.tencent.rca.common.enums.AlertType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 告警分析请求 (详细设计文档 2.2).
 *
 * @param alertId         告警唯一标识
 * @param serviceName     服务名
 * @param env             环境
 * @param alertSource     告警来源
 * @param alertType       告警类型 (可为空, 空则由路由引擎兜底分类)
 * @param timeWindow      分析时间窗口
 * @param rawAlertPayload 告警原文
 */
public record AnalyzeRequest(
        @NotBlank String alertId,
        @NotBlank String serviceName,
        String env,
        @NotNull AlertSource alertSource,
        AlertType alertType,
        @NotNull @Valid TimeWindowDto timeWindow,
        Map<String, Object> rawAlertPayload) {

    /**
     * 时间窗口 DTO.
     *
     * @param start 起始时间
     * @param end   结束时间
     */
    public record TimeWindowDto(
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end) {
    }
}
