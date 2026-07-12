package com.tencent.rca.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tencent.rca.config.RcaProperties;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.repository.AlertCaseRepository;
import com.tencent.rca.repository.cache.RcaCacheService;
import com.tencent.rca.repository.entity.AlertCaseEntity;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DedupEngine 单元测试: 命中缓存/命中活跃案卷/新建三条路径. 缓存与仓储 mock.
 */
@ExtendWith(MockitoExtension.class)
class DedupEngineTest {

    @Mock
    private RcaCacheService cacheService;
    @Mock
    private AlertCaseRepository alertCaseRepository;
    @Mock
    private CaseService caseService;

    private DedupEngine dedupEngine;

    @BeforeEach
    void setUp() {
        RcaProperties properties = new RcaProperties();
        dedupEngine = new DedupEngine(cacheService, alertCaseRepository, caseService, properties);
    }

    @Test
    void shouldMergeWhenFingerprintCached() {
        when(cacheService.getFingerprintCaseId("fp")).thenReturn(Optional.of(100L));

        DedupResult result = dedupEngine.check(alert("fp"));

        assertThat(result.isNew()).isFalse();
        assertThat(result.caseId()).isEqualTo(100L);
        verify(caseService).appendOccurrence(100L);
        verify(caseService, never()).createCase(any());
    }

    @Test
    void shouldMergeWhenActiveCaseInDb() {
        when(cacheService.getFingerprintCaseId("fp")).thenReturn(Optional.empty());
        AlertCaseEntity active = new AlertCaseEntity();
        active.setId(200L);
        when(alertCaseRepository.findFirstByFingerprintAndStatusInOrderByLastSeenAtDesc(eq("fp"), any()))
                .thenReturn(Optional.of(active));

        DedupResult result = dedupEngine.check(alert("fp"));

        assertThat(result.isNew()).isFalse();
        assertThat(result.caseId()).isEqualTo(200L);
        verify(caseService).appendOccurrence(200L);
        verify(cacheService).acquireFingerprint(eq("fp"), eq(200L), anyLong());
    }

    @Test
    void shouldCreateNewCaseWhenNoHit() {
        when(cacheService.getFingerprintCaseId("fp")).thenReturn(Optional.empty());
        when(alertCaseRepository.findFirstByFingerprintAndStatusInOrderByLastSeenAtDesc(eq("fp"), any()))
                .thenReturn(Optional.empty());
        AlertCaseEntity created = new AlertCaseEntity();
        created.setId(300L);
        when(caseService.createCase(any())).thenReturn(created);

        DedupResult result = dedupEngine.check(alert("fp"));

        assertThat(result.isNew()).isTrue();
        assertThat(result.caseId()).isEqualTo(300L);
        verify(cacheService).acquireFingerprint(eq("fp"), eq(300L), anyLong());
    }

    @Test
    void shouldTreatAsNewWhenFingerprintBlank() {
        AlertCaseEntity created = new AlertCaseEntity();
        created.setId(400L);
        when(caseService.createCase(any())).thenReturn(created);

        DedupResult result = dedupEngine.check(alert(""));

        assertThat(result.isNew()).isTrue();
        assertThat(result.caseId()).isEqualTo(400L);
        verify(cacheService, never()).acquireFingerprint(anyString(), anyLong(), anyLong());
    }

    private AlertContext alert(String fingerprint) {
        return new AlertContext("a1", "svc", "prod", null, null, null, fingerprint, Map.of());
    }
}
