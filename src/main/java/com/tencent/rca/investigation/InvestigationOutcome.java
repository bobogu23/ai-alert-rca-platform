package com.tencent.rca.investigation;

import com.tencent.rca.common.enums.SuspectedCause;
import com.tencent.rca.domain.Evidence;
import java.util.List;
import java.util.Optional;

/**
 * 迭代调查的最终产出 (迭代取证-证伪闭环).
 *
 * @param confirmedCause 被证实的根因方向(未证实任何方向时为空)
 * @param verifications  各轮验证结果, 记录完整取证-证伪轨迹
 * @param evidences      调查过程中产出的可追溯证据, 供并入证据集合
 */
public record InvestigationOutcome(
        Optional<SuspectedCause> confirmedCause,
        List<VerificationResult> verifications,
        List<Evidence> evidences) {
}
