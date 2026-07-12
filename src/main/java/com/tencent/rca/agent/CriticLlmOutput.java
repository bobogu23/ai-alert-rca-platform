package com.tencent.rca.agent;

import java.util.List;

/**
 * CriticAgent 中 LLM 语义校验的原始输出契约 (详细设计文档 4.3、4.4).
 * LLM 仅做语义判断: 核对根因假设是否曲解了证据的原始含义, 不承担事实性校验 (那部分由本地代码完成).
 *
 * @param consistent 假设与证据语义是否一致
 * @param issues     语义层面发现的问题列表 (consistent 为 false 时给出具体原因)
 */
public record CriticLlmOutput(
        boolean consistent,
        List<String> issues) {
}
