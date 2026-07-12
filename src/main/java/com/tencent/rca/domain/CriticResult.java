package com.tencent.rca.domain;

import java.util.List;

/**
 * Critic 校验 Agent 的输出 (详细设计文档 4.3).
 *
 * @param passed        是否通过校验
 * @param rejectReasons 未通过时的打回原因列表
 */
public record CriticResult(
        boolean passed,
        List<String> rejectReasons) {
}
