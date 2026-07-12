package com.tencent.rca.investigation;

import com.tencent.rca.common.enums.SuspectedCause;
import com.tencent.rca.config.RcaProperties;
import com.tencent.rca.domain.AlertContext;
import com.tencent.rca.domain.Evidence;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 迭代取证-证伪调查服务 (Agent 协作优化, 详细设计文档 6.3).
 * 以日志 Agent 的疑似依赖为起点, 通过"规则决策树 -> 定向验证 -> 证实/证伪 -> 转向"的闭环把根因分析透彻.
 * 例如日志判定疑似 Redis 超时, 则定向验证 Redis 指标; 若证伪, 依决策树转向 GC、再转向网络, 直至证实或收敛.
 * 决策完全由规则驱动(确定性、可测、无 LLM 成本); 每轮验证由 HypothesisVerifier 基于真实指标阈值判定.
 * 设最大轮次上限, 超限则用当前证据收敛, 避免打转.
 */
@Slf4j
@Service
public class InvestigationService {

    private final HypothesisVerifier verifier;
    private final RcaProperties properties;

    public InvestigationService(HypothesisVerifier verifier, RcaProperties properties) {
        this.verifier = verifier;
        this.properties = properties;
    }

    /**
     * 从日志线索出发, 迭代取证直至证实某根因方向或收敛.
     *
     * @param context    告警上下文
     * @param startCause 起点疑似根因方向(来自日志 Agent 的 suspectedDependency)
     * @return 调查产出(证实的方向、验证轨迹与证据)
     */
    public InvestigationOutcome investigate(AlertContext context, SuspectedCause startCause) {
        int maxRounds = Math.max(1, properties.getInvestigation().getMaxRounds());
        Set<SuspectedCause> visited = EnumSet.noneOf(SuspectedCause.class);
        List<VerificationResult> verifications = new ArrayList<>();
        List<Evidence> evidences = new ArrayList<>();
        Optional<SuspectedCause> confirmed = Optional.empty();

        SuspectedCause current = startCause;
        for (int round = 1; round <= maxRounds; round++) {
            SuspectedCause target = nextTarget(current, visited);
            if (target == SuspectedCause.UNKNOWN) {
                log.info("[InvestigationService] 第 {} 轮无更多可验证方向, 结束调查", round);
                break;
            }
            visited.add(target);
            log.info("[InvestigationService] 第 {} 轮定向验证: {}", round, target);

            VerificationResult result = verifier.verify(context, target);
            verifications.add(result);
            result.evidence().ifPresent(evidences::add);

            if (result.confirmed()) {
                confirmed = Optional.of(target);
                log.info("[InvestigationService] 第 {} 轮证实根因方向: {}, 结束调查", round, target);
                break;
            }
            // 证伪或不可判定: 以当前已验证方向为基准继续转向
            current = target;
        }
        return new InvestigationOutcome(confirmed, verifications, evidences);
    }

    /**
     * 规则决策树: 给定当前方向与已验证集合, 返回下一个待验证方向. 无更多方向时返回 UNKNOWN.
     * 转向顺序体现常见故障排查经验: 先查显式依赖, 再查 GC, 再查网络, 最后资源.
     */
    private SuspectedCause nextTarget(SuspectedCause current, Set<SuspectedCause> visited) {
        List<SuspectedCause> chain = switch (current) {
            case REDIS -> List.of(SuspectedCause.REDIS, SuspectedCause.GC, SuspectedCause.NETWORK,
                    SuspectedCause.RESOURCE);
            case DB -> List.of(SuspectedCause.DB, SuspectedCause.GC, SuspectedCause.NETWORK,
                    SuspectedCause.RESOURCE);
            case DOWNSTREAM -> List.of(SuspectedCause.DOWNSTREAM, SuspectedCause.NETWORK, SuspectedCause.GC,
                    SuspectedCause.RESOURCE);
            case RESOURCE -> List.of(SuspectedCause.RESOURCE, SuspectedCause.GC, SuspectedCause.NETWORK);
            case GC -> List.of(SuspectedCause.GC, SuspectedCause.NETWORK, SuspectedCause.RESOURCE);
            case NETWORK -> List.of(SuspectedCause.NETWORK, SuspectedCause.GC, SuspectedCause.RESOURCE);
            // 起点无明确线索时, 走通用排查链
            default -> List.of(SuspectedCause.GC, SuspectedCause.NETWORK, SuspectedCause.RESOURCE);
        };
        for (SuspectedCause candidate : chain) {
            if (!visited.contains(candidate)) {
                return candidate;
            }
        }
        return SuspectedCause.UNKNOWN;
    }
}
