package com.tencent.rca.governance;

import java.util.List;

/**
 * 级联根因合并结果 (详细设计文档 6.2).
 *
 * @param rootCaseId       根因案卷 ID (无法合并时为空)
 * @param cascadedCaseIds  被判定为级联受影响的案卷 ID 列表
 */
public record CascadeResult(
        Long rootCaseId,
        List<Long> cascadedCaseIds) {
}
