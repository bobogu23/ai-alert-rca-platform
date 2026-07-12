package com.tencent.rca.investigation;

import com.tencent.rca.common.enums.SuspectedCause;
import com.tencent.rca.domain.Evidence;
import java.util.Optional;

/**
 * 单个疑似根因的证实/证伪结果 (迭代取证-证伪闭环).
 *
 * @param cause       被验证的疑似根因方向
 * @param confirmed   是否被证实(阈值判定成立)
 * @param conclusive  数据是否充分可判定(数据缺失时为 false, 表示既未证实也未证伪)
 * @param summary     判定摘要(指标当前值 vs 阈值)
 * @param evidence    产出的可追溯证据(数据缺失时为空)
 */
public record VerificationResult(
        SuspectedCause cause,
        boolean confirmed,
        boolean conclusive,
        String summary,
        Optional<Evidence> evidence) {
}
