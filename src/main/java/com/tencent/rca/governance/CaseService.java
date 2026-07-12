package com.tencent.rca.governance;

import com.tencent.rca.common.enums.CaseStatus;
import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.common.exception.RcaException;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.repository.AlertCaseRepository;
import com.tencent.rca.repository.entity.AlertCaseEntity;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 案卷状态机服务 (详细设计文档 6.1、7.7).
 * 负责案卷创建、查询与状态流转. 状态流转经乐观锁(version)保证并发安全, 非法流转被拒;
 * 已 ANALYZED/ANALYZE_FAILED 的案卷不回退, 保证幂等.
 */
@Slf4j
@Service
public class CaseService {

    /** 活跃案卷状态集合, 供去重归并判断. */
    public static final Set<CaseStatus> ACTIVE_STATUSES =
            EnumSet.of(CaseStatus.OPEN, CaseStatus.ANALYZING, CaseStatus.ANALYZED);

    private static final int MAX_LOCK_RETRIES = 3;

    private final AlertCaseRepository alertCaseRepository;
    private final Map<CaseStatus, Set<CaseStatus>> allowedTransitions = buildTransitions();

    public CaseService(AlertCaseRepository alertCaseRepository) {
        this.alertCaseRepository = alertCaseRepository;
    }

    /**
     * 创建新案卷, 初始状态 OPEN.
     *
     * @param alert 告警上下文
     * @return 落库后的案卷实体
     */
    public AlertCaseEntity createCase(AlertContext alert) {
        AlertCaseEntity entity = new AlertCaseEntity();
        entity.setFingerprint(alert.fingerprint());
        entity.setServiceName(alert.serviceName());
        entity.setAlertSource(alert.alertSource());
        entity.setAlertType(alert.alertType());
        entity.setStatus(CaseStatus.OPEN);
        LocalDateTime now = LocalDateTime.now();
        entity.setFirstSeenAt(now);
        entity.setLastSeenAt(now);
        entity.setMergedAlertCount(1);
        return alertCaseRepository.save(entity);
    }

    /**
     * 按 ID 查询案卷.
     *
     * @param caseId 案卷 ID
     * @return 案卷实体
     */
    public Optional<AlertCaseEntity> getById(Long caseId) {
        return alertCaseRepository.findById(caseId);
    }

    /**
     * 归并一次重复告警: 递增计数并刷新最近出现时间.
     *
     * @param caseId 案卷 ID
     */
    public void appendOccurrence(Long caseId) {
        updateWithRetry(caseId, entity -> {
            int count = entity.getMergedAlertCount() == null ? 1 : entity.getMergedAlertCount();
            entity.setMergedAlertCount(count + 1);
            entity.setLastSeenAt(LocalDateTime.now());
        });
    }

    /**
     * 标记案卷进入分析中.
     *
     * @param caseId 案卷 ID
     */
    public void markAnalyzing(Long caseId) {
        transition(caseId, CaseStatus.ANALYZING, entity -> { });
    }

    /**
     * 标记分析成功并写入根因结论与报告地址.
     *
     * @param caseId     案卷 ID
     * @param summary    根因结论摘要
     * @param confidence 置信度
     * @param reportUrl  报告地址 (可为空)
     * @param degradeNote 降级说明 (可为空)
     */
    public void completeAnalysis(Long caseId,
                                 String summary,
                                 ConfidenceLevel confidence,
                                 String reportUrl,
                                 String degradeNote) {
        transition(caseId, CaseStatus.ANALYZED, entity -> {
            entity.setRootCauseSummary(summary);
            entity.setConfidenceLevel(confidence);
            entity.setReportUrl(reportUrl);
            entity.setDegradeNote(degradeNote);
        });
    }

    /**
     * 标记分析失败(已产出兜底报告).
     *
     * @param caseId      案卷 ID
     * @param degradeNote 失败/降级说明
     */
    public void failAnalysis(Long caseId, String degradeNote) {
        transition(caseId, CaseStatus.ANALYZE_FAILED, entity -> entity.setDegradeNote(degradeNote));
    }

    /**
     * 将案卷标记为级联受影响并关联根因案卷.
     *
     * @param caseId     案卷 ID
     * @param rootCaseId 根因案卷 ID
     */
    public void markCascaded(Long caseId, Long rootCaseId) {
        transition(caseId, CaseStatus.CASCADED, entity -> entity.setRootCauseCaseId(rootCaseId));
    }

    /**
     * 更新报告地址.
     *
     * @param caseId    案卷 ID
     * @param reportUrl 报告地址
     */
    public void updateReportUrl(Long caseId, String reportUrl) {
        updateWithRetry(caseId, entity -> entity.setReportUrl(reportUrl));
    }

    /**
     * 通用状态流转, 供人工反馈等场景使用.
     *
     * @param caseId       案卷 ID
     * @param targetStatus 目标状态
     */
    public void transitionTo(Long caseId, CaseStatus targetStatus) {
        transition(caseId, targetStatus, entity -> { });
    }

    private void transition(Long caseId, CaseStatus targetStatus, Consumer<AlertCaseEntity> mutator) {
        updateWithRetry(caseId, entity -> {
            CaseStatus current = entity.getStatus();
            if (current == targetStatus) {
                mutator.accept(entity);
                return;
            }
            if (!isTransitionAllowed(current, targetStatus)) {
                throw new RcaException(String.format(
                        "非法状态流转: %s -> %s (case=%d)", current, targetStatus, caseId));
            }
            entity.setStatus(targetStatus);
            mutator.accept(entity);
        });
    }

    private void updateWithRetry(Long caseId, Consumer<AlertCaseEntity> mutator) {
        int attempt = 0;
        while (true) {
            AlertCaseEntity entity = alertCaseRepository.findById(caseId)
                    .orElseThrow(() -> new RcaException("案卷不存在: " + caseId));
            mutator.accept(entity);
            try {
                alertCaseRepository.save(entity);
                return;
            } catch (ObjectOptimisticLockingFailureException ex) {
                attempt++;
                if (attempt >= MAX_LOCK_RETRIES) {
                    throw new RcaException("案卷并发更新冲突, 重试耗尽: " + caseId, ex);
                }
                log.warn("案卷 {} 乐观锁冲突, 第 {} 次重试", caseId, attempt);
            }
        }
    }

    private boolean isTransitionAllowed(CaseStatus current, CaseStatus target) {
        Set<CaseStatus> targets = allowedTransitions.get(current);
        return targets != null && targets.contains(target);
    }

    private Map<CaseStatus, Set<CaseStatus>> buildTransitions() {
        Map<CaseStatus, Set<CaseStatus>> map = new EnumMap<>(CaseStatus.class);
        map.put(CaseStatus.OPEN, EnumSet.of(CaseStatus.ANALYZING, CaseStatus.CASCADED));
        map.put(CaseStatus.ANALYZING, EnumSet.of(CaseStatus.ANALYZED, CaseStatus.ANALYZE_FAILED));
        map.put(CaseStatus.ANALYZED,
                EnumSet.of(CaseStatus.ACKNOWLEDGED, CaseStatus.RESOLVED, CaseStatus.FALSE_POSITIVE));
        map.put(CaseStatus.ANALYZE_FAILED,
                EnumSet.of(CaseStatus.ACKNOWLEDGED, CaseStatus.RESOLVED, CaseStatus.FALSE_POSITIVE));
        map.put(CaseStatus.ACKNOWLEDGED, EnumSet.of(CaseStatus.RESOLVED, CaseStatus.FALSE_POSITIVE));
        return map;
    }
}
