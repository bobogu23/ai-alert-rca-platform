package com.tencent.rca.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencent.rca.common.enums.AlertSource;
import com.tencent.rca.common.enums.AlertType;
import com.tencent.rca.common.enums.CaseStatus;
import com.tencent.rca.repository.entity.AlertCaseEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * AlertCaseRepository JPA 集成测试: 使用本地 MySQL, 校验字段映射、查询方法与乐观锁版本号.
 * 需真实数据库凭据, 默认跳过; 设置环境变量 RCA_DB_IT=true 且提供 DB_USER/DB_PASSWORD 后运行,
 * 避免因本机 MySQL 密码差异导致的构建失败.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RCA_DB_IT", matches = "true")
class AlertCaseRepositoryTest {

    @Autowired
    private AlertCaseRepository repository;

    @Test
    void shouldPersistAndAssignVersionAndId() {
        AlertCaseEntity saved = repository.saveAndFlush(newCase("fp-persist", CaseStatus.OPEN));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isNotNull();
    }

    @Test
    void shouldFindActiveCaseByFingerprint() {
        AlertCaseEntity saved = repository.saveAndFlush(newCase("fp-active", CaseStatus.ANALYZING));

        Optional<AlertCaseEntity> found = repository
                .findFirstByFingerprintAndStatusInOrderByLastSeenAtDesc(
                        "fp-active", List.of(CaseStatus.OPEN, CaseStatus.ANALYZING, CaseStatus.ANALYZED));

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void shouldNotFindWhenStatusNotActive() {
        repository.saveAndFlush(newCase("fp-resolved", CaseStatus.RESOLVED));

        Optional<AlertCaseEntity> found = repository
                .findFirstByFingerprintAndStatusInOrderByLastSeenAtDesc(
                        "fp-resolved", List.of(CaseStatus.OPEN, CaseStatus.ANALYZING, CaseStatus.ANALYZED));

        assertThat(found).isEmpty();
    }

    private AlertCaseEntity newCase(String fingerprint, CaseStatus status) {
        AlertCaseEntity entity = new AlertCaseEntity();
        entity.setFingerprint(fingerprint);
        entity.setServiceName("svc");
        entity.setAlertSource(AlertSource.CLS);
        entity.setAlertType(AlertType.LOG_THRESHOLD);
        entity.setStatus(status);
        LocalDateTime now = LocalDateTime.now();
        entity.setFirstSeenAt(now);
        entity.setLastSeenAt(now);
        entity.setMergedAlertCount(1);
        return entity;
    }
}
