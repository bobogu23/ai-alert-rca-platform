package com.tencent.rca.governance;

import com.tencent.rca.api.dto.FeedbackRequest;
import com.tencent.rca.common.enums.CaseStatus;
import com.tencent.rca.common.enums.FeedbackType;
import com.tencent.rca.observability.RcaMetrics;
import com.tencent.rca.repository.AlertFeedbackRepository;
import com.tencent.rca.repository.entity.AlertCaseEntity;
import com.tencent.rca.repository.entity.AlertFeedbackEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 人工反馈服务 (在线自我完善方案 07 文档 2、6.2).
 * 承载反馈飞轮的采集环节: 落库反馈台账 + 记录诊断快照 + 推进案卷状态 + 上报采纳率等飞轮指标.
 * 反馈落库与指标上报绝不阻塞主流程; 状态流转复用 CaseService 已有的终态迁移能力.
 */
@Slf4j
@Service
public class FeedbackService {

    private static final int DEFAULT_FEEDBACKER_LEVEL = 1;

    private final AlertFeedbackRepository feedbackRepository;
    private final CaseService caseService;
    private final RcaMetrics metrics;

    public FeedbackService(AlertFeedbackRepository feedbackRepository,
                           CaseService caseService,
                           RcaMetrics metrics) {
        this.feedbackRepository = feedbackRepository;
        this.caseService = caseService;
        this.metrics = metrics;
    }

    /**
     * 记录一次人工反馈: 落库 + 推进案卷状态 + 上报指标.
     *
     * @param caseId  案卷 ID
     * @param request 反馈请求
     */
    public void record(Long caseId, FeedbackRequest request) {
        Optional<AlertCaseEntity> caseOpt = caseService.getById(caseId);
        if (caseOpt.isEmpty()) {
            throw new IllegalArgumentException("案卷不存在: " + caseId);
        }
        AlertCaseEntity caseEntity = caseOpt.get();

        persistFeedback(caseId, caseEntity, request);
        metrics.recordFeedback(request.feedbackType().name());
        // 推进案卷状态到对应终态; 非法流转由 CaseService 拦截, 此处兜底记录不抛出以免反馈丢失
        try {
            caseService.transitionTo(caseId, targetStatus(request.feedbackType()));
        } catch (RuntimeException ex) {
            log.warn("案卷 {} 反馈后状态流转失败, 反馈已落库: {}", caseId, ex.getMessage());
        }
        log.info("收到人工反馈: caseId={}, type={}, errorCategory={}, by={}",
                caseId, request.feedbackType(), request.errorCategory(), request.feedbackBy());
    }

    private void persistFeedback(Long caseId, AlertCaseEntity caseEntity, FeedbackRequest request) {
        AlertFeedbackEntity entity = new AlertFeedbackEntity();
        entity.setCaseId(caseId);
        entity.setFingerprint(caseEntity.getFingerprint());
        entity.setServiceName(caseEntity.getServiceName());
        entity.setFeedbackType(request.feedbackType());
        // 诊断快照: 记录系统当时给出的结论, 供后续对比学习
        entity.setAiRootCauseSummary(caseEntity.getRootCauseSummary());
        entity.setAiConfidence(caseEntity.getConfidenceLevel());
        // 人工纠错内容
        entity.setActualRootCauseDirection(request.actualRootCauseDirection());
        entity.setActualRootCauseSummary(request.actualRootCauseSummary());
        entity.setCorrectEvidenceHint(request.correctEvidenceHint());
        entity.setErrorCategory(request.errorCategory());
        entity.setSuggestion(request.suggestion());
        // 治理字段
        entity.setFeedbackBy(request.feedbackBy());
        entity.setFeedbackerLevel(request.feedbackerLevel() == null
                ? DEFAULT_FEEDBACKER_LEVEL : request.feedbackerLevel());
        entity.setAdoptedToExperience(false);
        entity.setFeedbackAt(LocalDateTime.now());
        feedbackRepository.save(entity);
    }

    private CaseStatus targetStatus(FeedbackType feedbackType) {
        return switch (feedbackType) {
            case CONFIRMED -> CaseStatus.RESOLVED;
            case FALSE_POSITIVE -> CaseStatus.FALSE_POSITIVE;
            case PARTIAL, REJECTED -> CaseStatus.ACKNOWLEDGED;
        };
    }
}
