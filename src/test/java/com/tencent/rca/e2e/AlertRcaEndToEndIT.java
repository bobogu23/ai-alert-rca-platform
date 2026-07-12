package com.tencent.rca.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.tencent.rca.common.enums.CaseStatus;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.governance.DedupEngine;
import com.tencent.rca.governance.DedupResult;
import com.tencent.rca.mcp.ClsMcpClient;
import com.tencent.rca.mcp.CodeRepoMcpClient;
import com.tencent.rca.mcp.GalileoMcpClient;
import com.tencent.rca.notify.NotificationGateway;
import com.tencent.rca.orchestrator.Orchestrator;
import com.tencent.rca.repository.cache.RcaCacheService;
import com.tencent.rca.repository.entity.AlertCaseEntity;
import com.tencent.rca.governance.CaseService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * 全链路端到端集成测试: 从接收错误日志告警到输出归因报告.
 * 真实依赖: MySQL(案卷落库) + LiteLLM(根因推理/校验/建议). Mock 依赖: Redis 缓存、三路 MCP 数据源、企微推送.
 * 错误日志为本地造数. 默认跳过, 设置 RCA_LLM_IT=true 且 RCA_DB_IT=true 后运行.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RCA_LLM_IT", matches = "true")
@EnabledIfEnvironmentVariable(named = "RCA_DB_IT", matches = "true")
class AlertRcaEndToEndIT {

    @Autowired
    private DedupEngine dedupEngine;
    @Autowired
    private Orchestrator orchestrator;
    @Autowired
    private CaseService caseService;

    // Redis: mock 判重与中间结果缓存
    @MockBean
    private RcaCacheService cacheService;
    // 三路数据源: mock, 由本地造数
    @MockBean
    private ClsMcpClient clsMcpClient;
    @MockBean
    private GalileoMcpClient galileoMcpClient;
    @MockBean
    private CodeRepoMcpClient codeRepoMcpClient;
    // 通知网关: mock 避免真实企微外呼(同时规避 mock 渠道在网关构造期返回 null 类型的问题)
    @MockBean
    private NotificationGateway notificationGateway;

    @Test
    void shouldAnalyzeErrorLogAlertEndToEndAndProduceReport() {
        stubRedisAsCacheMiss();
        stubMcpWithFakeErrorLogs();
        stubNotificationGateway();

        AlertContext alert = fakeErrorLogAlert();
        // 经去重建卷(Redis mock 未命中 -> 新建), 再异步交编排执行真实归因
        DedupResult dedup = dedupEngine.check(alert);
        assertThat(dedup.isNew()).isTrue();
        Long caseId = dedup.caseId();

        boolean accepted = orchestrator.analyzeAsync(caseId, alert);
        assertThat(accepted).isTrue();

        // 异步执行, 轮询等待案卷进入终态(上限需覆盖 overall 超时预算)
        await().atMost(Duration.ofSeconds(200)).pollInterval(Duration.ofSeconds(2)).until(() ->
                caseService.getById(caseId)
                        .map(AlertCaseEntity::getStatus)
                        .filter(status -> status == CaseStatus.ANALYZED || status == CaseStatus.ANALYZE_FAILED)
                        .isPresent());

        AlertCaseEntity finalCase = caseService.getById(caseId).orElseThrow();
        // 有真实 LLM 且有日志证据, 期望正常产出归因报告(ANALYZED)
        assertThat(finalCase.getStatus()).isEqualTo(CaseStatus.ANALYZED);
        assertThat(finalCase.getRootCauseSummary()).isNotBlank();
        assertThat(finalCase.getConfidenceLevel()).isNotNull();
        assertThat(finalCase.getMergedAlertCount()).isGreaterThanOrEqualTo(1);
    }

    private void stubRedisAsCacheMiss() {
        when(cacheService.getFingerprintCaseId(anyString())).thenReturn(Optional.empty());
        when(cacheService.acquireFingerprint(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(cacheService.getAgentResult(anyLong(), anyString(), any())).thenReturn(Optional.empty());
    }

    private void stubMcpWithFakeErrorLogs() {
        // 造一批错误日志样例: 同类 NPE 刷屏 + 少量下游超时, 供指纹聚类与根因推理
        List<String> fakeLogs = List.of(
                "\t\n"
                        + "[UserReadingServiceImpl]:getWeekReadTime. parameters: [{\"appId\":10,\"areaId\":40,\"version\":1210,\"userId\":4316637251,\"deviceId\":\"c96f191ca026eba34c52688790c11d90\",\"clientIp\":\"10.110.77.41\",\"channel\":\"AppStore\",\"source\":\"AppStore\",\"userIp\":\"2607:fb90:2c8c:593b:21ae:e2fe:f857:3069\",\"language\":\"en\",\"country\":\"US\",\"authorId\":0,\"readPreference\":1,\"showLanguage\":\"en\"}]\n"
                        + "\n"
                        + "span_id\n"
                        + "\n"
                        + "791f518ac086c354\n"
                        + "\n"
                        + "stack_trace\n"
                        + "\n"
                        + "io.lettuce.core.RedisCommandTimeoutException: Command timed out after 200 millisecond(s)\n"
                        + "\tat io.lettuce.core.internal.ExceptionFactory.createTimeoutException(ExceptionFactory.java:59)\n"
                        + "\tat io.lettuce.core.internal.Futures.awaitOrCancel(Futures.java:246)\n"
                        + "\tat io.lettuce.core.LettuceFutures.awaitOrCancel(LettuceFutures.java:74)\n"
                        + "\tat org.springframework.data.redis.connection.lettuce.LettuceConnection.await(LettuceConnection.java:1083)\n"
                        + "\t... 92 common frames omitted\n"
                        + "Wrapped by: org.springframework.dao.QueryTimeoutException: Redis command timed out; nested exception is io.lettuce.core.RedisCommandTimeoutException: Command timed out after 200 millisecond(s)\n"
                        + "\tat org.springframework.data.redis.connection.lettuce.LettuceExceptionConverter.convert(LettuceExceptionConverter.java:70)\n"
                        + "\tat org.springframework.data.redis.connection.lettuce.LettuceExceptionConverter.convert(LettuceExceptionConverter.java:41)\n"
                        + "\tat org.springframework.data.redis.PassThroughExceptionTranslationStrategy.translate(PassThroughExceptionTranslationStrategy.java:44)\n"
                        + "\tat org.springframework.data.redis.FallbackExceptionTranslationStrategy.translate(FallbackExceptionTranslationStrategy.java:42)\n"
                        + "\tat org.springframework.data.redis.connection.lettuce.LettuceConnection.convertLettuceAccessException(LettuceConnection.java:277)\n"
                        + "\tat org.springframework.data.redis.connection.lettuce.LettuceConnection.await(LettuceConnection.java:1085)\n"
                        + "\tat org.springframework.data.redis.connection.lettuce.LettuceConnection.lambda$doInvoke$4(LettuceConnection.java:938)\n"
                        + "\tat org.springframework.data.redis.connection.lettuce.LettuceInvoker$Synchronizer.invoke(LettuceInvoker.java:665)\n"
                        + "\tat org.springframework.data.redis.connection.lettuce.LettuceInvoker.just(LettuceInvoker.java:94)\n"
                        + "\tat org.springframework.data.redis.connection.lettuce.LettuceStringCommands.get(LettuceStringCommands.java:55)\n"
                        + "\tat org.springframework.data.redis.connection.DefaultedRedisConnection.get(DefaultedRedisConnection.java:279)\n"
                        + "\tat org.springframework.data.redis.connection.DefaultStringRedisConnection.get(DefaultStringRedisConnection.java:438)\n"
                        + "\tat org.springframework.data.redis.core.DefaultValueOperations$1.inRedis(DefaultValueOperations.java:58)\n"
                        + "\tat org.springframework.data.redis.core.AbstractOperations$ValueDeserializingRedisCallback.doInRedis(AbstractOperations.java:61)\n"
                        + "\tat org.springframework.data.redis.core.RedisTemplate.execute(RedisTemplate.java:224)\n"
                        + "\tat org.springframework.data.redis.core.RedisTemplate.execute(RedisTemplate.java:191)\n"
                        + "\tat org.springframework.data.redis.core.AbstractOperations.execute(AbstractOperations.java:97)\n"
                        + "\tat org.springframework.data.redis.core.DefaultValueOperations.get(DefaultValueOperations.java:54)\n"
                        + "\tat com.yuewen.webnovel.service.userreading.service.impl.UserReadingServiceImpl.getWeekReadTime(UserReadingServiceImpl.java:98)\n"
                        + "\tat com.yuewen.webnovel.service.userreading.service.impl.UserReadingServiceImpl.getWeekReadTime(UserReadingServiceImpl.java:89)\n"
                        + "\t... 6 frames excluded\n"
                        + "\tat com.yuewen.webnovel.bean.aspect.ExceptionChecker.handle(ExceptionChecker.java:36)\n"
                        + "\t... 13 frames excluded\n"
                        + "\tat com.yuewen.webnovel.service.userreading.impl.UserReadingFacade.getWeekReadTime(UserReadingFacade.java:60)\n"
                        + "\tat com.yuewen.webnovel.service.userreading.impl.UserReadImpl.getWeekReadTime(UserReadImpl.java:38)\n"
                        + "\t... 47 frames excluded\n"
                        + "\tat com.yuewen.webnovel.dubbo.rpc.tars.TarsProtocol$1.reply(TarsProtocol.java:67)\n"
                        + "\t... 8 frames excluded");
        when(clsMcpClient.fetchErrorLogSamples(any(AlertContext.class), anyInt())).thenReturn(fakeLogs);
        // Galileo 泛查返回空(触发对应维度降级但不阻断); 定向验证指标按指纹造数: redis 指标超阈值以证实 Redis 超时
        when(galileoMcpClient.fetchMetrics(any())).thenReturn(Map.of());
        when(galileoMcpClient.fetchSlowTraces(any())).thenReturn(Map.of());
        when(galileoMcpClient.fetchMetricByName(any(), anyString())).thenAnswer(inv -> {
            String metricName = inv.getArgument(1);
            if (metricName != null && metricName.contains("redis")) {
                // 观测 500ms 超过 200ms 阈值 -> 证实 Redis 超时
                return Map.of("value", 500.0);
            }
            return Map.of();
        });
        when(codeRepoMcpClient.searchCode(anyString(), anyInt())).thenReturn(List.of());
        when(codeRepoMcpClient.fetchRecentReleases(anyString())).thenReturn(List.of());
    }

    private void stubNotificationGateway() {
        when(notificationGateway.dispatch(any(), any())).thenReturn(Map.of());
    }

    private AlertContext fakeErrorLogAlert() {
        // 不设 alertType, 让路由走 LLM 兜底; 也可显式 LOG_THRESHOLD. 这里造异常日志告警场景
        return new AlertContext(
                "e2e-" + System.currentTimeMillis(),
                "userreadservice",
                "prod",
                com.tencent.rca.common.enums.AlertSource.CLS,
                com.tencent.rca.common.enums.AlertType.LOG_THRESHOLD,
                new com.tencent.rca.domain.TimeWindow(
                        java.time.OffsetDateTime.parse("2026-07-11T01:02:00+08:00"),
                        java.time.OffsetDateTime.parse("2026-07-11T01:07:00+08:00")),
                "e2e-fp-" + System.currentTimeMillis(),
                Map.of("rule", "error_log_threshold", "threshold", 100));
    }
}
