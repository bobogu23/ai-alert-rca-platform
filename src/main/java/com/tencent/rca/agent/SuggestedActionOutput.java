package com.tencent.rca.agent;

import java.util.List;

/**
 * ReportGeneratorAgent 中 LLM 生成运维建议动作的输出契约 (详细设计文档 3.1).
 * LLM 仅基于已确认的根因结论产出可执行的处置建议, 不引入新的事实性结论.
 *
 * @param suggestedActions 建议动作列表, 每条为一句可操作的处置步骤
 */
public record SuggestedActionOutput(
        List<String> suggestedActions) {
}
