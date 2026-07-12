package com.tencent.rca.api;

import com.tencent.rca.api.dto.AnalyzeRequest;
import com.tencent.rca.api.dto.AnalyzeResponse;
import com.tencent.rca.api.dto.CaseView;
import com.tencent.rca.api.dto.FeedbackRequest;
import com.tencent.rca.common.enums.CaseStatus;
import com.tencent.rca.common.enums.ChannelType;
import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.TimeWindow;
import com.tencent.rca.governance.CaseService;
import com.tencent.rca.governance.DedupEngine;
import com.tencent.rca.governance.DedupResult;
import com.tencent.rca.governance.SuppressionEngine;
import com.tencent.rca.governance.SuppressionResult;
import com.tencent.rca.notify.NotificationGateway;
import com.tencent.rca.notify.NotificationMessage;
import com.tencent.rca.orchestrator.Orchestrator;
import com.tencent.rca.repository.entity.AlertCaseEntity;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 归因系统对外 REST 接入层 (详细设计文档 2.2、4.6).
 * analyze 先经抑制与去重治理再异步交编排, 立即返回不阻塞; 另提供案卷查询、手动补推与人工反馈接口.
 * 入参校验失败由 Bean Validation 自动返回 400.
 */
@Slf4j
@RestController
@RequestMapping("/api/rca")
public class RcaController {

    private final DedupEngine dedupEngine;
    private final SuppressionEngine suppressionEngine;
    private final Orchestrator orchestrator;
    private final CaseService caseService;
    private final NotificationGateway notificationGateway;

    public RcaController(DedupEngine dedupEngine,
                         SuppressionEngine suppressionEngine,
                         Orchestrator orchestrator,
                         CaseService caseService,
                         NotificationGateway notificationGateway) {
        this.dedupEngine = dedupEngine;
        this.suppressionEngine = suppressionEngine;
        this.orchestrator = orchestrator;
        this.caseService = caseService;
        this.notificationGateway = notificationGateway;
    }

    /**
     * 接收告警并触发归因分析.
     *
     * @param request 分析请求
     * @return 受理结果 (202)
     */
    @PostMapping("/analyze")
    public ResponseEntity<AnalyzeResponse> analyze(@Valid @RequestBody AnalyzeRequest request) {
        AlertContext alert = toAlertContext(request);

        SuppressionResult suppression = suppressionEngine.evaluate(alert);
        if (suppression.suppressed()) {
            log.info("告警被抑制({}), 不触发分析: alertId={}", suppression.ruleType(), alert.alertId());
            return ResponseEntity.accepted().body(new AnalyzeResponse(null, "SUPPRESSED"));
        }

        DedupResult dedup = dedupEngine.check(alert);
        if (!dedup.isNew()) {
            log.info("告警归并到既有案卷: caseId={}", dedup.caseId());
            return ResponseEntity.accepted()
                    .body(new AnalyzeResponse(String.valueOf(dedup.caseId()), "MERGED"));
        }

        boolean accepted = orchestrator.analyzeAsync(dedup.caseId(), alert);
        if (!accepted) {
            // 并发限流拒绝: 案卷已被置为失败兜底, 返回 503 提示稍后重试
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new AnalyzeResponse(String.valueOf(dedup.caseId()), "REJECTED"));
        }
        return ResponseEntity.accepted()
                .body(new AnalyzeResponse(String.valueOf(dedup.caseId()), "ANALYZING"));
    }

    /**
     * 查询案卷/报告视图.
     *
     * @param caseId 案卷 ID
     * @return 案卷视图或 404
     */
    @GetMapping("/cases/{caseId}")
    public ResponseEntity<CaseView> getCase(@PathVariable Long caseId) {
        return caseService.getById(caseId)
                .map(entity -> ResponseEntity.ok(toView(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 手动补推报告 (详细设计文档 7.5).
     *
     * @param caseId 案卷 ID
     * @return 补推结果
     */
    @PostMapping("/cases/{caseId}/resend")
    public ResponseEntity<Void> resend(@PathVariable Long caseId) {
        Optional<AlertCaseEntity> entity = caseService.getById(caseId);
        if (entity.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        notificationGateway.dispatch(toMessage(entity.get()), List.of(ChannelType.WECOM));
        return ResponseEntity.ok().build();
    }

    /**
     * 提交人工反馈 (详细设计文档 4.6).
     *
     * @param caseId  案卷 ID
     * @param request 反馈请求
     * @return 反馈受理结果
     */
    @PostMapping("/cases/{caseId}/feedback")
    public ResponseEntity<Void> feedback(@PathVariable Long caseId, @Valid @RequestBody FeedbackRequest request) {
        if (caseService.getById(caseId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // 第一版反馈用于后续 Prompt 迭代, 暂以日志留痕并推进案卷状态; 专用反馈存储表在后续版本引入
        log.info("收到人工反馈: caseId={}, verdict={}, comment={}", caseId, request.verdict(), request.comment());
        caseService.transitionTo(caseId, targetStatus(request));
        return ResponseEntity.ok().build();
    }

    private CaseStatus targetStatus(FeedbackRequest request) {
        return switch (request.verdict()) {
            case INACCURATE -> CaseStatus.FALSE_POSITIVE;
            default -> CaseStatus.ACKNOWLEDGED;
        };
    }

    private AlertContext toAlertContext(AnalyzeRequest request) {
        TimeWindow timeWindow = new TimeWindow(request.timeWindow().start(), request.timeWindow().end());
        String fingerprint = computeFingerprint(request);
        return new AlertContext(
                request.alertId(),
                request.serviceName(),
                request.env(),
                request.alertSource(),
                request.alertType(),
                timeWindow,
                fingerprint,
                request.rawAlertPayload());
    }

    private String computeFingerprint(AnalyzeRequest request) {
        String raw = request.serviceName() + "|"
                + request.alertSource() + "|"
                + request.alertType() + "|"
                + String.valueOf(request.rawAlertPayload());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 标准算法, 正常不会发生; 退化为原文哈希保证仍具确定性
            return Integer.toHexString(raw.hashCode());
        }
    }

    private CaseView toView(AlertCaseEntity entity) {
        return new CaseView(
                entity.getId(),
                entity.getServiceName(),
                entity.getStatus() == null ? null : entity.getStatus().name(),
                entity.getRootCauseSummary(),
                entity.getConfidenceLevel() == null ? null : entity.getConfidenceLevel().name(),
                entity.getDegradeNote(),
                entity.getReportUrl(),
                entity.getMergedAlertCount());
    }

    private NotificationMessage toMessage(AlertCaseEntity entity) {
        ConfidenceLevel confidence = entity.getConfidenceLevel() == null
                ? ConfidenceLevel.LOW : entity.getConfidenceLevel();
        String summary = StringUtils.hasText(entity.getRootCauseSummary())
                ? entity.getRootCauseSummary() : "该案卷暂无根因结论";
        return new NotificationMessage(
                String.valueOf(entity.getId()),
                "[归因] " + entity.getServiceName(),
                summary,
                confidence,
                entity.getReportUrl(),
                summary);
    }
}
