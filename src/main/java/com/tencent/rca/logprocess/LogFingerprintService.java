package com.tencent.rca.logprocess;

import com.tencent.rca.domain.LogAnalysisResult.ExceptionCategory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 日志指纹归一化压缩服务 (详细设计文档 5.2).
 * 用确定性正则规则库将高基数动态字段替换为占位符, 把海量相似日志压缩为数十个指纹类别,
 * 每类只保留代表性样例, 不依赖 LLM, 保证速度快、成本低、结果稳定可复现.
 */
@Component
public class LogFingerprintService {

    /** 时间戳, 如 2026-07-09 23:13:01.123. */
    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
    /** UUID. */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** 32 位十六进制串, 常见于 TraceId. */
    private static final Pattern HEX32 = Pattern.compile("\\b[0-9a-fA-F]{32}\\b");
    /** IP 或 IP:Port. */
    private static final Pattern ENDPOINT = Pattern.compile(
            "\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?\\b");
    /** 连续 6 位以上数字, 视为高基数 ID/金额/订单号. */
    private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d{6,}\\b");
    /** 显式 traceId 键值, 用于提取代表性 TraceID. */
    private static final Pattern TRACE_KV = Pattern.compile(
            "(?i)trace[_-]?id[\"'=:\\s]+([0-9a-fA-F]{8,64})");

    /**
     * 将一批原始日志按归一化指纹聚类, 输出按数量降序的类别列表.
     *
     * @param rawLogs 原始日志文本列表
     * @return 聚类后的异常类别列表
     */
    public List<ExceptionCategory> cluster(List<String> rawLogs) {
        if (rawLogs == null || rawLogs.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String logLine : rawLogs) {
            String fingerprint = normalize(logLine);
            groups.computeIfAbsent(fingerprint, key -> new ArrayList<>()).add(logLine);
        }
        long total = rawLogs.size();
        List<ExceptionCategory> categories = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            List<String> members = entry.getValue();
            long count = members.size();
            double ratio = (double) count / total;
            String sampleLog = members.get(0);
            String sampleTrace = extractTraceId(sampleLog);
            categories.add(new ExceptionCategory(entry.getKey(), count, ratio, sampleLog, sampleTrace));
        }
        categories.sort((left, right) -> Long.compare(right.count(), left.count()));
        return categories;
    }

    /**
     * 对单条日志做归一化, 替换动态字段为占位符. 替换顺序需先长后短, 避免破坏前序匹配.
     *
     * @param logLine 原始日志
     * @return 归一化后的指纹文本
     */
    public String normalize(String logLine) {
        if (logLine == null) {
            return "";
        }
        String text = logLine;
        text = TIMESTAMP.matcher(text).replaceAll("<time>");
        text = UUID_PATTERN.matcher(text).replaceAll("<uuid>");
        text = HEX32.matcher(text).replaceAll("<trace>");
        text = ENDPOINT.matcher(text).replaceAll("<endpoint>");
        text = LONG_NUMBER.matcher(text).replaceAll("<id>");
        return text.strip();
    }

    private String extractTraceId(String logLine) {
        Matcher kv = TRACE_KV.matcher(logLine);
        if (kv.find()) {
            return kv.group(1);
        }
        Matcher hex = HEX32.matcher(logLine);
        if (hex.find()) {
            return hex.group();
        }
        return "";
    }
}
