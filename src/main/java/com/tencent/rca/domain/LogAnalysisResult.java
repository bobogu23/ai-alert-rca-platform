package com.tencent.rca.domain;

import java.util.List;

/**
 * 日志分析 Agent 的结构化输出.
 *
 * @param topExceptionCategories 聚类后的 Top 异常类别
 * @param prunedCallChain        裁剪后的业务调用链
 * @param suspectedDependency    疑似异常依赖类型 (如 downstream/db/redis/business)
 * @param evidences              产出的证据列表
 * @param degraded               是否因数据源失败而降级
 * @param degradeNote            降级说明
 */
public record LogAnalysisResult(
        List<ExceptionCategory> topExceptionCategories,
        String prunedCallChain,
        String suspectedDependency,
        List<Evidence> evidences,
        boolean degraded,
        String degradeNote) {

    /**
     * 单个异常类别聚类结果.
     *
     * @param fingerprint 归一化指纹
     * @param count       出现次数
     * @param ratio       占比
     * @param sampleLog   代表性样例日志
     * @param sampleTrace 代表性 TraceID
     */
    public record ExceptionCategory(
            String fingerprint,
            long count,
            double ratio,
            String sampleLog,
            String sampleTrace) {
    }
}
