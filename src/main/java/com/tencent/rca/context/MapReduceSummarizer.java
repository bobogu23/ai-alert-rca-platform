package com.tencent.rca.context;

import com.tencent.rca.llm.LlmClient;
import com.tencent.rca.llm.ModelTier;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Map-Reduce 分层摘要器 (详细设计文档 5.4).
 * 面向 Trace 等大数据量场景: Map 阶段用轻量模型对每个分片做局部摘要, Reduce 阶段汇总为全局摘要,
 * 保证单次 LLM 调用上下文规模受控. 优先级低, 数据量可控时无需使用.
 */
@Slf4j
@Component
public class MapReduceSummarizer {

    private static final String MAP_SYSTEM_PROMPT =
            "你是数据摘要助手. 请用不超过三句话概括以下片段的关键信息, 保留数值与异常特征, 不得编造.";
    private static final String REDUCE_SYSTEM_PROMPT =
            "你是数据汇总助手. 请将以下多段局部摘要整合为一段全局摘要, 去重并突出最关键的异常与指标变化.";

    private final LlmClient llmClient;

    public MapReduceSummarizer(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 对多个数据分片做分层摘要.
     *
     * @param chunks 数据分片列表
     * @return 全局摘要文本
     */
    public String summarize(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        List<String> partials = new ArrayList<>();
        for (String chunk : chunks) {
            partials.add(llmClient.complete(ModelTier.LIGHTWEIGHT, MAP_SYSTEM_PROMPT, chunk));
        }
        if (partials.size() == 1) {
            return partials.get(0);
        }
        String reduceInput = String.join("\n---\n", partials);
        return llmClient.complete(ModelTier.LIGHTWEIGHT, REDUCE_SYSTEM_PROMPT, reduceInput);
    }
}
