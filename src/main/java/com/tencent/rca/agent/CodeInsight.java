package com.tencent.rca.agent;

import java.util.List;

/**
 * CodeContextAgent 中 LLM 负责的配置隐患识别输出契约.
 * 代码片段、最近发布记录等事实性数据由本地从 MCP 获取, LLM 仅产出隐患研判.
 *
 * @param riskPoints 识别出的配置隐患点列表, 每条为简短描述, 无法识别时为空列表
 */
public record CodeInsight(
        List<String> riskPoints) {
}
