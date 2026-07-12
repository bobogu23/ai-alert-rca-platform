package com.tencent.rca.domain;

import com.tencent.rca.common.enums.AlertSource;
import com.tencent.rca.common.enums.AlertType;
import java.util.Map;

/**
 * 归一化后的告警上下文, 贯穿整个分析流程.
 *
 * @param alertId         告警唯一标识
 * @param serviceName     服务名
 * @param env             环境 (prod/pre/test)
 * @param alertSource     告警来源
 * @param alertType       告警类型
 * @param timeWindow      分析时间窗口
 * @param fingerprint     告警指纹
 * @param rawAlertPayload 告警原文
 */
public record AlertContext(
        String alertId,
        String serviceName,
        String env,
        AlertSource alertSource,
        AlertType alertType,
        TimeWindow timeWindow,
        String fingerprint,
        Map<String, Object> rawAlertPayload) {
}
