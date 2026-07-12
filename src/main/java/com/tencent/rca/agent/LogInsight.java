package com.tencent.rca.agent;

/**
 * LogAnalysisAgent 中 LLM 负责的语义判断输出契约.
 * 事实性数据(异常聚类、证据引用)由本地确定性算法产出, LLM 仅产出以下两项语义结论.
 *
 * @param prunedCallChain     基于异常日志梳理出的业务调用链
 * @param suspectedDependency 疑似异常依赖类型, 取值限定为
 *                            downstream/db/redis/mq/business/resource/unknown
 */
public record LogInsight(
        String prunedCallChain,
        String suspectedDependency) {
}
