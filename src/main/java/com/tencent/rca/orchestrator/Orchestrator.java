package com.tencent.rca.orchestrator;

import com.tencent.rca.agent.CodeContextAgent;
import com.tencent.rca.agent.CriticAgent;
import com.tencent.rca.agent.LogAnalysisAgent;
import com.tencent.rca.agent.ReportGeneratorAgent;
import com.tencent.rca.agent.RootCauseReasoningAgent;
import com.tencent.rca.agent.TraceMetricAgent;
import com.tencent.rca.common.enums.ChannelType;
import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.common.enums.SuspectedCause;
import com.tencent.rca.config.NotifyProperties;
import com.tencent.rca.config.RcaProperties;
import com.tencent.rca.context.ContextBudgetManager;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.CodeContextResult;
import com.tencent.rca.domain.CriticResult;
import com.tencent.rca.domain.Evidence;
import com.tencent.rca.domain.EvidenceBundle;
import com.tencent.rca.domain.LogAnalysisResult;
import com.tencent.rca.domain.RcaReport;
import com.tencent.rca.domain.ReasoningResult;
import com.tencent.rca.domain.RootCauseHypothesis;
import com.tencent.rca.domain.TraceMetricResult;
import com.tencent.rca.fault.TimeLimitExceededException;
import com.tencent.rca.fault.TimeLimiter;
import com.tencent.rca.governance.CaseService;
import com.tencent.rca.investigation.InvestigationOutcome;
import com.tencent.rca.investigation.InvestigationService;
import com.tencent.rca.notify.NotificationGateway;
import com.tencent.rca.notify.NotificationMessage;
import com.tencent.rca.notify.NotificationResult;
import com.tencent.rca.observability.RcaMetrics;
import com.tencent.rca.report.FallbackReportBuilder;
import com.tencent.rca.repository.cache.RcaCacheService;
import com.tencent.rca.routing.RouteDecision;
import com.tencent.rca.routing.RoutingEngine;
import com.tencent.rca.security.SensitiveDataMasker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 归因流程编排服务 (详细设计文档 2.2、7 全章).
 * 第一版流程唯一承载者: 路由 -> 并行证据收集(允许部分失败) -> 证据脱敏与预算裁剪 -> 根因推理 -> Critic 校验循环
 * -> 报告生成/兜底 -> 案卷状态更新 -> 报告分发, 并施加分层超时、异常隔离降级、幂等中间结果缓存与指标上报.
 * 去重/抑制与案卷创建在接入层完成, 本服务针对已创建的 caseId 执行分析.
 */
@Slf4j
@Service
public class Orchestrator {

    private static final long AGENT_CACHE_TTL_SECONDS = 1800L;

    private final RoutingEngine routingEngine;
    private final LogAnalysisAgent logAnalysisAgent;
    private final TraceMetricAgent traceMetricAgent;
    private final CodeContextAgent codeContextAgent;
    private final RootCauseReasoningAgent reasoningAgent;
    private final CriticAgent criticAgent;
    private final ReportGeneratorAgent reportGeneratorAgent;
    private final FallbackReportBuilder fallbackReportBuilder;
    private final ContextBudgetManager contextBudgetManager;
    private final SensitiveDataMasker masker;
    private final InvestigationService investigationService;
    private final CaseService caseService;
    private final RcaCacheService cacheService;
    private final NotificationGateway notificationGateway;
    private final RcaMetrics metrics;
    private final TimeLimiter timeLimiter;
    private final RcaProperties properties;
    private final NotifyProperties notifyProperties;
    /** 提交闸门: 有界队列 + 固定并发, 限制同时进行的分析数, 对下游做背压保护. */
    private final ThreadPoolExecutor submissionExecutor;
    /** 内部 fan-out 执行器: 供单次分析内三路证据 Agent 并行使用. */
    private final ExecutorService fanOutExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public Orchestrator(RoutingEngine routingEngine,
                        LogAnalysisAgent logAnalysisAgent,
                        TraceMetricAgent traceMetricAgent,
                        CodeContextAgent codeContextAgent,
                        RootCauseReasoningAgent reasoningAgent,
                        CriticAgent criticAgent,
                        ReportGeneratorAgent reportGeneratorAgent,
                        FallbackReportBuilder fallbackReportBuilder,
                        ContextBudgetManager contextBudgetManager,
                        SensitiveDataMasker masker,
                        InvestigationService investigationService,
                        CaseService caseService,
                        RcaCacheService cacheService,
                        NotificationGateway notificationGateway,
                        RcaMetrics metrics,
                        TimeLimiter timeLimiter,
                        RcaProperties properties,
                        NotifyProperties notifyProperties) {
        this.routingEngine = routingEngine;
        this.logAnalysisAgent = logAnalysisAgent;
        this.traceMetricAgent = traceMetricAgent;
        this.codeContextAgent = codeContextAgent;
        this.reasoningAgent = reasoningAgent;
        this.criticAgent = criticAgent;
        this.reportGeneratorAgent = reportGeneratorAgent;
        this.fallbackReportBuilder = fallbackReportBuilder;
        this.contextBudgetManager = contextBudgetManager;
        this.masker = masker;
        this.investigationService = investigationService;
        this.caseService = caseService;
        this.cacheService = cacheService;
        this.notificationGateway = notificationGateway;
        this.metrics = metrics;
        this.timeLimiter = timeLimiter;
        this.properties = properties;
        this.notifyProperties = notifyProperties;
        RcaProperties.Concurrency concurrency = properties.getConcurrency();
        int poolSize = Math.max(1, concurrency.getMaxConcurrentAnalyses());
        int queueCapacity = Math.max(1, concurrency.getQueueCapacity());
        // 核心=最大=poolSize, 有界队列满则由 AbortPolicy 拒绝, 形成背压; keepAlive 对固定池无实际影响
        this.submissionExecutor = new ThreadPoolExecutor(
                poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 异步执行归因分析. 立即返回, 不阻塞调用方.
     * 经提交闸门限流: 同时进行的分析数达上限且等待队列已满时, 拒绝本次提交并将案卷置为失败, 避免压垮下游.
     *
     * @param caseId 已创建的案卷 ID
     * @param alert  告警上下文
     * @return 成功受理返回 true, 因限流被拒绝返回 false
     */
    public boolean analyzeAsync(Long caseId, AlertContext alert) {
        try {
            submissionExecutor.submit(() -> runSafely(caseId, alert));
            return true;
        } catch (RejectedExecutionException ex) {
            log.warn("分析并发已达上限且队列已满, 拒绝案卷 {} 的分析提交", caseId);
            metrics.recordAnalyzeRejected();
            handleRejected(caseId, alert);
            return false;
        }
    }

    private void handleRejected(Long caseId, AlertContext alert) {
        RcaReport report = fallbackReportBuilder.buildFallback(
                caseId, alert, emptyBundle(alert), "系统繁忙, 分析并发已达上限, 已排队拒绝");
        finalizeCase(caseId, alert, report);
    }

    private void runSafely(Long caseId, AlertContext alert) {
        long startMs = System.currentTimeMillis();
        RcaReport report;
        try {
            report = timeLimiter.callWithTimeout(
                    () -> runPipeline(caseId, alert),
                    properties.getTimeout().getOverallMs(),
                    "rca-overall");
        } catch (TimeLimitExceededException ex) {
            log.warn("案卷 {} 整体分析超时, 走兜底", caseId, ex);
            report = fallbackReportBuilder.buildFallback(caseId, alert, emptyBundle(alert), "整体分析超时");
        } catch (RuntimeException ex) {
            log.error("案卷 {} 分析异常, 走兜底", caseId, ex);
            report = fallbackReportBuilder.buildFallback(caseId, alert, emptyBundle(alert), "分析异常: " + ex.getMessage());
        }
        finalizeCase(caseId, alert, report);
        metrics.recordAnalyzeDuration(System.currentTimeMillis() - startMs);
    }

    private RcaReport runPipeline(Long caseId, AlertContext alert) {
        caseService.markAnalyzing(caseId);
        RouteDecision decision = routingEngine.route(alert);
        log.info("案卷 {} 路由决策: {}", caseId, decision.note());

        List<String> notes = new ArrayList<>();
        EvidenceBundle rawBundle = collectEvidence(caseId, alert, decision.activatedAgents(), notes);
        if (!rawBundle.hasAnyEvidence()) {
            return fallbackReportBuilder.buildFallback(caseId, alert, rawBundle, "三路证据源全部失败");
        }

        // 迭代取证-证伪闭环: 以日志线索为起点定向验证根因方向, 把验证证据并入证据集合
        EvidenceBundle investigatedBundle = investigateAndEnrich(caseId, alert, rawBundle, notes);
        EvidenceBundle bundle = refineBundle(alert, investigatedBundle);
        ReasoningResult reasoning;
        try {
            reasoning = reasoningAgent.reason(bundle);
        } catch (RuntimeException ex) {
            log.warn("案卷 {} 根因推理失败, 走兜底: {}", caseId, ex.getMessage());
            return fallbackReportBuilder.buildFallback(caseId, alert, bundle, "根因推理不可用: " + ex.getMessage());
        }

        ReasoningResult verifiedReasoning = criticLoop(caseId, reasoning, bundle, notes);
        String degradeNote = notes.isEmpty() ? null : String.join("; ", notes);
        return reportGeneratorAgent.generate(caseId, alert, verifiedReasoning, bundle, degradeNote);
    }

    private EvidenceBundle investigateAndEnrich(Long caseId,
                                                AlertContext alert,
                                                EvidenceBundle rawBundle,
                                                List<String> notes) {
        if (!properties.getInvestigation().isEnabled() || rawBundle.logResult().isEmpty()) {
            return rawBundle;
        }
        LogAnalysisResult logResult = rawBundle.logResult().get();
        SuspectedCause startCause = SuspectedCause.fromDependency(logResult.suspectedDependency());
        log.info("案卷 {} 进入迭代取证闭环, 起点线索: {}", caseId, startCause);

        InvestigationOutcome outcome;
        try {
            outcome = investigationService.investigate(alert, startCause);
        } catch (RuntimeException ex) {
            log.warn("案卷 {} 迭代取证失败, 跳过取证增强: {}", caseId, ex.getMessage());
            notes.add("迭代取证异常, 已跳过: " + ex.getMessage());
            return rawBundle;
        }

        outcome.confirmedCause().ifPresentOrElse(
                cause -> notes.add("迭代取证证实根因方向: " + cause),
                () -> notes.add("迭代取证未证实明确方向, 结论标注未完全证实"));
        if (outcome.evidences().isEmpty()) {
            return rawBundle;
        }
        // 将取证证据并入日志维度证据, 供根因推理综合研判
        List<Evidence> merged = new ArrayList<>(logResult.evidences());
        merged.addAll(outcome.evidences());
        LogAnalysisResult enriched = new LogAnalysisResult(
                logResult.topExceptionCategories(), logResult.prunedCallChain(), logResult.suspectedDependency(),
                merged, logResult.degraded(), logResult.degradeNote());
        return new EvidenceBundle(alert, Optional.of(enriched),
                rawBundle.traceMetricResult(), rawBundle.codeContextResult());
    }

    private EvidenceBundle collectEvidence(Long caseId,
                                           AlertContext alert,
                                           Set<String> activatedAgents,
                                           List<String> notes) {
        Future<LogAnalysisResult> logFuture = activatedAgents.contains(RoutingEngine.LOG_AGENT)
                ? fanOutExecutor.submit(() -> resolveLog(caseId, alert)) : null;
        Future<TraceMetricResult> traceFuture = activatedAgents.contains(RoutingEngine.TRACE_AGENT)
                ? fanOutExecutor.submit(() -> resolveTrace(caseId, alert)) : null;
        Future<CodeContextResult> codeFuture = activatedAgents.contains(RoutingEngine.CODE_AGENT)
                ? fanOutExecutor.submit(() -> resolveCode(caseId, alert)) : null;

        long agentMs = properties.getTimeout().getAgentMs();
        Optional<LogAnalysisResult> logResult = await(logFuture, agentMs, RoutingEngine.LOG_AGENT, notes);
        Optional<TraceMetricResult> traceResult = await(traceFuture, agentMs, RoutingEngine.TRACE_AGENT, notes);
        Optional<CodeContextResult> codeResult = await(codeFuture, agentMs, RoutingEngine.CODE_AGENT, notes);

        logResult.filter(LogAnalysisResult::degraded)
                .ifPresent(r -> markDegraded(RoutingEngine.LOG_AGENT, r.degradeNote(), notes));
        traceResult.filter(TraceMetricResult::degraded)
                .ifPresent(r -> markDegraded(RoutingEngine.TRACE_AGENT, r.degradeNote(), notes));
        codeResult.filter(CodeContextResult::degraded)
                .ifPresent(r -> markDegraded(RoutingEngine.CODE_AGENT, r.degradeNote(), notes));
        return new EvidenceBundle(alert, logResult, traceResult, codeResult);
    }

    private LogAnalysisResult resolveLog(Long caseId, AlertContext alert) {
        Optional<LogAnalysisResult> cached = cacheService.getAgentResult(
                caseId, RoutingEngine.LOG_AGENT, LogAnalysisResult.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        LogAnalysisResult result = logAnalysisAgent.analyze(alert);
        cacheService.putAgentResult(caseId, RoutingEngine.LOG_AGENT, result, AGENT_CACHE_TTL_SECONDS);
        return result;
    }

    private TraceMetricResult resolveTrace(Long caseId, AlertContext alert) {
        Optional<TraceMetricResult> cached = cacheService.getAgentResult(
                caseId, RoutingEngine.TRACE_AGENT, TraceMetricResult.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        TraceMetricResult result = traceMetricAgent.analyze(alert);
        cacheService.putAgentResult(caseId, RoutingEngine.TRACE_AGENT, result, AGENT_CACHE_TTL_SECONDS);
        return result;
    }

    private CodeContextResult resolveCode(Long caseId, AlertContext alert) {
        Optional<CodeContextResult> cached = cacheService.getAgentResult(
                caseId, RoutingEngine.CODE_AGENT, CodeContextResult.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        CodeContextResult result = codeContextAgent.analyze(alert);
        cacheService.putAgentResult(caseId, RoutingEngine.CODE_AGENT, result, AGENT_CACHE_TTL_SECONDS);
        return result;
    }

    private <T> Optional<T> await(Future<T> future, long timeoutMs, String agentName, List<String> notes) {
        if (future == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(future.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException ex) {
            future.cancel(true);
            markDegraded(agentName, "执行超时", notes);
            return Optional.empty();
        } catch (ExecutionException ex) {
            markDegraded(agentName, "执行异常: " + ex.getMessage(), notes);
            return Optional.empty();
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            markDegraded(agentName, "被中断", notes);
            return Optional.empty();
        }
    }

    private void markDegraded(String agentName, String note, List<String> notes) {
        metrics.recordAgentDegraded(agentName);
        notes.add(agentName + " 降级(" + (StringUtils.hasText(note) ? note : "未知原因") + ")");
    }

    private ReasoningResult criticLoop(Long caseId,
                                       ReasoningResult reasoning,
                                       EvidenceBundle bundle,
                                       List<String> notes) {
        long deadline = System.currentTimeMillis() + properties.getTimeout().getCriticLoopMs();
        int maxIterations = properties.getCritic().getMaxIterations();
        ReasoningResult current = reasoning;
        for (int i = 1; i <= maxIterations; i++) {
            Optional<CriticResult> verified = safeVerify(current, bundle, notes);
            if (verified.isEmpty()) {
                // Critic 不可用: 保留硬校验结果, 置信度不高于 MEDIUM (详细设计文档 7.4)
                return capConfidence(current, ConfidenceLevel.MEDIUM);
            }
            if (verified.get().passed()) {
                return current;
            }
            metrics.recordCriticReject();
            notes.add("Critic 第 " + i + " 轮打回: " + String.join(" | ", verified.get().rejectReasons()));
            if (i >= maxIterations || System.currentTimeMillis() > deadline) {
                break;
            }
            current = reasoningAgent.reason(bundle);
        }
        // 超轮次或超时仍未通过, 强制降级为低置信度 (详细设计文档 7.4)
        notes.add("Critic 未通过, 强制降级为低置信度");
        return capConfidence(current, ConfidenceLevel.LOW);
    }

    private Optional<CriticResult> safeVerify(ReasoningResult reasoning, EvidenceBundle bundle, List<String> notes) {
        try {
            return Optional.of(criticAgent.verify(reasoning, bundle));
        } catch (RuntimeException ex) {
            log.warn("Critic 校验不可用, 跳过深度校验: {}", ex.getMessage());
            notes.add("Critic 校验不可用, 置信度不高于 MEDIUM");
            return Optional.empty();
        }
    }

    private ReasoningResult capConfidence(ReasoningResult reasoning, ConfidenceLevel maxLevel) {
        List<RootCauseHypothesis> capped = new ArrayList<>();
        List<RootCauseHypothesis> source = reasoning.hypotheses() == null ? List.of() : reasoning.hypotheses();
        for (RootCauseHypothesis hypothesis : source) {
            ConfidenceLevel level = strongerThan(hypothesis.confidence(), maxLevel)
                    ? maxLevel : hypothesis.confidence();
            capped.add(new RootCauseHypothesis(
                    hypothesis.summary(), level, hypothesis.supportingEvidence(), hypothesis.counterEvidenceOrGaps()));
        }
        return new ReasoningResult(capped, reasoning.impactScope());
    }

    private boolean strongerThan(ConfidenceLevel level, ConfidenceLevel max) {
        return level != null && level.ordinal() < max.ordinal();
    }

    private EvidenceBundle refineBundle(AlertContext alert, EvidenceBundle raw) {
        // 先脱敏各证据文本(保留 sourceRef 以维持 Critic 引用校验), 再按 token 预算全局裁剪
        Optional<LogAnalysisResult> maskedLog = raw.logResult().map(this::maskLog);
        Optional<TraceMetricResult> maskedTrace = raw.traceMetricResult().map(this::maskTrace);
        Optional<CodeContextResult> maskedCode = raw.codeContextResult().map(this::maskCode);
        EvidenceBundle masked = new EvidenceBundle(alert, maskedLog, maskedTrace, maskedCode);

        List<Evidence> pruned = contextBudgetManager.prune(
                masked.allEvidences(), properties.getContextBudget().getReasoningAgentTokens());
        Set<Evidence> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        retained.addAll(pruned);

        Optional<LogAnalysisResult> finalLog = maskedLog.map(r -> filterLog(r, retained));
        Optional<TraceMetricResult> finalTrace = maskedTrace.map(r -> filterTrace(r, retained));
        Optional<CodeContextResult> finalCode = maskedCode.map(r -> filterCode(r, retained));
        return new EvidenceBundle(alert, finalLog, finalTrace, finalCode);
    }

    private LogAnalysisResult maskLog(LogAnalysisResult r) {
        return new LogAnalysisResult(r.topExceptionCategories(), r.prunedCallChain(), r.suspectedDependency(),
                maskEvidences(r.evidences()), r.degraded(), r.degradeNote());
    }

    private TraceMetricResult maskTrace(TraceMetricResult r) {
        return new TraceMetricResult(r.bottleneckType(), r.metricSummary(), r.topSlowSpans(),
                maskEvidences(r.evidences()), r.degraded(), r.degradeNote());
    }

    private CodeContextResult maskCode(CodeContextResult r) {
        return new CodeContextResult(r.codeSnippets(), r.recentChanges(), r.riskPoints(),
                maskEvidences(r.evidences()), r.degraded(), r.degradeNote());
    }

    private List<Evidence> maskEvidences(List<Evidence> evidences) {
        List<Evidence> masked = new ArrayList<>();
        for (Evidence evidence : evidences) {
            masked.add(new Evidence(masker.mask(evidence.description()), evidence.type(),
                    evidence.sourceRef(), masker.mask(evidence.rawSnippet())));
        }
        return masked;
    }

    private LogAnalysisResult filterLog(LogAnalysisResult r, Set<Evidence> retained) {
        return new LogAnalysisResult(r.topExceptionCategories(), r.prunedCallChain(), r.suspectedDependency(),
                retain(r.evidences(), retained), r.degraded(), r.degradeNote());
    }

    private TraceMetricResult filterTrace(TraceMetricResult r, Set<Evidence> retained) {
        return new TraceMetricResult(r.bottleneckType(), r.metricSummary(), r.topSlowSpans(),
                retain(r.evidences(), retained), r.degraded(), r.degradeNote());
    }

    private CodeContextResult filterCode(CodeContextResult r, Set<Evidence> retained) {
        return new CodeContextResult(r.codeSnippets(), r.recentChanges(), r.riskPoints(),
                retain(r.evidences(), retained), r.degraded(), r.degradeNote());
    }

    private List<Evidence> retain(List<Evidence> evidences, Set<Evidence> retained) {
        List<Evidence> kept = new ArrayList<>();
        for (Evidence evidence : evidences) {
            if (retained.contains(evidence)) {
                kept.add(evidence);
            }
        }
        return kept;
    }

    private void finalizeCase(Long caseId, AlertContext alert, RcaReport report) {
        try {
            if (report.fallback()) {
                metrics.recordFallback();
                caseService.failAnalysis(caseId, report.degradeNote());
                metrics.recordAnalyzeCompleted("ANALYZE_FAILED");
            } else {
                caseService.completeAnalysis(caseId, report.conclusion(), report.confidence(),
                        null, report.degradeNote());
                metrics.recordAnalyzeCompleted("ANALYZED");
            }
        } catch (RuntimeException ex) {
            log.error("案卷 {} 状态更新失败", caseId, ex);
        }
        dispatch(caseId, alert, report);
    }

    private void dispatch(Long caseId, AlertContext alert, RcaReport report) {
        if (!notifyProperties.getWecom().isEnabled()) {
            return;
        }
        NotificationMessage message = new NotificationMessage(
                String.valueOf(caseId),
                "[归因] " + alert.serviceName(),
                report.conclusion(),
                report.confidence(),
                null,
                report.markdownBody());
        Map<ChannelType, NotificationResult> results =
                notificationGateway.dispatch(message, List.of(ChannelType.WECOM));
        results.forEach((channel, result) -> {
            if (!result.success()) {
                metrics.recordNotificationFailure(channel.name());
            }
        });
    }

    private EvidenceBundle emptyBundle(AlertContext alert) {
        return new EvidenceBundle(alert, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
