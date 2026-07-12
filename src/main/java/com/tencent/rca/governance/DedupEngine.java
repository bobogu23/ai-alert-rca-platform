package com.tencent.rca.governance;

import com.tencent.rca.config.RcaProperties;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.repository.AlertCaseRepository;
import com.tencent.rca.repository.cache.RcaCacheService;
import com.tencent.rca.repository.entity.AlertCaseEntity;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 告警去重引擎 (详细设计文档 6.1).
 * 短窗内同指纹归并到同一案卷: 先查 Redis 判重位, 再兜底查 MySQL 活跃案卷;
 * 命中则追加时间线(递增计数)并返回归并, 不重复触发分析; 否则建新案卷并写入 Redis 冷却窗.
 */
@Slf4j
@Service
public class DedupEngine {

    private final RcaCacheService cacheService;
    private final AlertCaseRepository alertCaseRepository;
    private final CaseService caseService;
    private final RcaProperties properties;

    public DedupEngine(RcaCacheService cacheService,
                       AlertCaseRepository alertCaseRepository,
                       CaseService caseService,
                       RcaProperties properties) {
        this.cacheService = cacheService;
        this.alertCaseRepository = alertCaseRepository;
        this.caseService = caseService;
        this.properties = properties;
    }

    /**
     * 判定告警是否为新告警, 并返回归属案卷.
     *
     * @param alert 告警上下文
     * @return 去重结果
     */
    public DedupResult check(AlertContext alert) {
        String fingerprint = alert.fingerprint();
        if (!StringUtils.hasText(fingerprint)) {
            log.warn("告警缺少指纹, 视为新告警不去重: alertId={}", alert.alertId());
            return newCase(alert);
        }

        Optional<Long> cached = cacheService.getFingerprintCaseId(fingerprint);
        if (cached.isPresent()) {
            caseService.appendOccurrence(cached.get());
            return new DedupResult(false, cached.get());
        }

        Optional<AlertCaseEntity> activeCase = alertCaseRepository
                .findFirstByFingerprintAndStatusInOrderByLastSeenAtDesc(fingerprint, CaseService.ACTIVE_STATUSES);
        if (activeCase.isPresent()) {
            Long caseId = activeCase.get().getId();
            caseService.appendOccurrence(caseId);
            refreshCooldown(fingerprint, caseId);
            return new DedupResult(false, caseId);
        }

        return newCase(alert);
    }

    private DedupResult newCase(AlertContext alert) {
        AlertCaseEntity created = caseService.createCase(alert);
        Long caseId = created.getId();
        if (StringUtils.hasText(alert.fingerprint())) {
            cacheService.acquireFingerprint(alert.fingerprint(), caseId, cooldownSeconds());
        }
        return new DedupResult(true, caseId);
    }

    private void refreshCooldown(String fingerprint, Long caseId) {
        // 已存在活跃案卷但 Redis 判重位缺失(如缓存过期), 重新占位维持冷却窗一致性
        cacheService.acquireFingerprint(fingerprint, caseId, cooldownSeconds());
    }

    private long cooldownSeconds() {
        return properties.getDedup().getCooldownSeconds();
    }
}
