package com.tencent.rca.notify;

import com.tencent.rca.common.enums.ChannelType;
import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.config.NotifyProperties;
import com.tencent.rca.fault.RetryExecutor;
import com.tencent.rca.fault.RetryableException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 企业微信群机器人 Webhook 通知渠道 (详细设计文档 3.2).
 * 将报告转换为企微 markdown 消息体推送. 网络抖动/5xx/限流经 RetryExecutor 指数退避重试;
 * 业务错误(errcode 非 0)不重试直接返回失败. 置信度做视觉区分(企微 markdown 仅支持 info/comment/warning 三色).
 */
@Slf4j
@Component
public class WecomChannel implements NotificationChannel {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final NotifyProperties notifyProperties;
    private final RetryExecutor retryExecutor;
    private final RestClient restClient;

    public WecomChannel(NotifyProperties notifyProperties,
                        RetryExecutor retryExecutor,
                        RestClient.Builder restClientBuilder) {
        this.notifyProperties = notifyProperties;
        this.retryExecutor = retryExecutor;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = restClientBuilder.requestFactory(factory).build();
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.WECOM;
    }

    @Override
    public NotificationResult send(NotificationMessage message) {
        NotifyProperties.Wecom wecom = notifyProperties.getWecom();
        if (!wecom.isEnabled()) {
            return new NotificationResult(false, "企业微信渠道已禁用");
        }
        if (!StringUtils.hasText(wecom.getWebhookUrl())) {
            return new NotificationResult(false, "企业微信 webhookUrl 未配置");
        }
        Map<String, Object> payload = buildPayload(message);
        try {
            return retryExecutor.execute(
                    () -> postToWebhook(wecom.getWebhookUrl(), payload),
                    wecom.getMaxRetries(),
                    "wecom-push");
        } catch (RuntimeException ex) {
            log.warn("企业微信推送最终失败: case={}, err={}", message.caseId(), ex.getMessage());
            return new NotificationResult(false, "企业微信推送失败: " + ex.getMessage());
        }
    }

    private NotificationResult postToWebhook(String webhookUrl, Map<String, Object> payload) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (request, resp) -> {
                        throw new RetryableException("企微服务端错误: " + resp.getStatusCode());
                    })
                    .onStatus(status -> status.value() == 429, (request, resp) -> {
                        throw new RetryableException("企微触发限流(429)");
                    })
                    .body(Map.class);
            return interpretResponse(response);
        } catch (ResourceAccessException ex) {
            throw new RetryableException("企微网络访问异常", ex);
        }
    }

    private NotificationResult interpretResponse(Map<?, ?> response) {
        if (response == null) {
            return new NotificationResult(false, "企微响应为空");
        }
        Object errcode = response.get("errcode");
        if (errcode instanceof Number number && number.intValue() == 0) {
            return new NotificationResult(true, null);
        }
        return new NotificationResult(false, "企微返回错误: " + response.get("errmsg"));
    }

    private Map<String, Object> buildPayload(NotificationMessage message) {
        String content = buildMarkdown(message);
        return Map.of("msgtype", "markdown", "markdown", Map.of("content", content));
    }

    private String buildMarkdown(NotificationMessage message) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(orEmpty(message.title())).append('\n')
                .append("> 置信度: <font color=\"").append(colorOf(message.confidence())).append("\">")
                .append(message.confidence()).append("</font>\n\n")
                .append(orEmpty(message.summary())).append('\n');
        if (StringUtils.hasText(message.reportUrl())) {
            builder.append("\n[查看完整报告](").append(message.reportUrl()).append(")\n");
        }
        appendFeedbackSection(builder, message.caseId());
        return builder.toString();
    }

    /**
     * 渲染人工反馈引导区块 (在线自我完善方案 07 文档 2.2、2.5).
     * 企微 markdown 不支持交互按钮, 故以带 feedbackType 参数的反馈链接模拟"采纳/部分/驳回/误报"四个入口,
     * 引导 SRE 就地一键反馈, 回流到 /api/rca/cases/{caseId}/feedback. 未配置反馈地址时不渲染.
     */
    private void appendFeedbackSection(StringBuilder builder, String caseId) {
        String baseUrl = notifyProperties.getFeedbackBaseUrl();
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(caseId)) {
            return;
        }
        builder.append("\n> 归因是否准确? 请一键反馈以帮助系统持续完善:\n")
                .append(feedbackLink("✅ 采纳", baseUrl, caseId, "CONFIRMED")).append("　")
                .append(feedbackLink("◐ 部分正确", baseUrl, caseId, "PARTIAL")).append('\n')
                .append(feedbackLink("✗ 驳回", baseUrl, caseId, "REJECTED")).append("　")
                .append(feedbackLink("🔕 误报", baseUrl, caseId, "FALSE_POSITIVE")).append('\n');
    }

    private String feedbackLink(String label, String baseUrl, String caseId, String feedbackType) {
        String url = baseUrl + "?caseId=" + caseId + "&feedbackType=" + feedbackType;
        return "[" + label + "](" + url + ")";
    }

    private String colorOf(ConfidenceLevel confidence) {
        if (confidence == ConfidenceLevel.HIGH) {
            return "info";
        }
        if (confidence == ConfidenceLevel.MEDIUM) {
            return "comment";
        }
        return "warning";
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
