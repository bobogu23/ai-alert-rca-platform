package com.tencent.rca.api.dto;

import com.tencent.rca.common.enums.FeedbackErrorCategory;
import com.tencent.rca.common.enums.FeedbackType;
import com.tencent.rca.common.enums.SuspectedCause;
import jakarta.validation.constraints.NotNull;

/**
 * 人工反馈请求 (在线自我完善方案 07 文档 2.2、2.3).
 * feedbackType 必填; 其余为 REJECTED/PARTIAL 场景的选填纠错内容, CONFIRMED/FALSE_POSITIVE 可全部为空.
 *
 * @param feedbackType             反馈类型 (必填)
 * @param actualRootCauseDirection 真实根因方向 (纠错时填)
 * @param actualRootCauseSummary   真实根因描述 (纠错时填)
 * @param correctEvidenceHint      正确的证据线索 (纠错时填)
 * @param errorCategory            错误类型 (驳回/部分正确时填)
 * @param suggestion               反馈人自由建议 (可为空)
 * @param feedbackBy               反馈人标识 (可为空)
 * @param feedbackerLevel          反馈人级别 1~3 (可为空, 默认 1)
 */
public record FeedbackRequest(
        @NotNull FeedbackType feedbackType,
        SuspectedCause actualRootCauseDirection,
        String actualRootCauseSummary,
        String correctEvidenceHint,
        FeedbackErrorCategory errorCategory,
        String suggestion,
        String feedbackBy,
        Integer feedbackerLevel) {
}
