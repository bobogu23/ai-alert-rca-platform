package com.tencent.rca.logprocess;

import com.tencent.rca.config.RcaProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 框架堆栈裁剪服务 (详细设计文档 5.3).
 * 依据业务包名白名单保留业务帧, 对业务帧之间被跳过的框架帧区间仅保留"框架调用入口帧"作为因果锚点,
 * 其余框架帧丢弃, 从而把 50~100 行原始堆栈压缩为业务关键帧, 减少 LLM 噪音.
 */
@Component
public class StackTracePruner {

    private static final String FRAME_PREFIX = "at ";

    private final RcaProperties properties;

    public StackTracePruner(RcaProperties properties) {
        this.properties = properties;
    }

    /**
     * 裁剪堆栈, 保留业务帧与框架区间入口帧.
     *
     * @param stackTrace 原始堆栈文本
     * @return 裁剪后的精简堆栈文本
     */
    public String prune(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return "";
        }
        List<String> businessPackages = properties.getStackPruning().getBusinessPackages();
        String[] lines = stackTrace.split("\\R");
        List<String> kept = new ArrayList<>();
        boolean frameworkEntryKept = false;
        int skipped = 0;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith(FRAME_PREFIX)) {
                String frame = line.substring(FRAME_PREFIX.length());
                if (isBusinessFrame(frame, businessPackages)) {
                    kept.add(raw);
                    frameworkEntryKept = false;
                } else if (!frameworkEntryKept) {
                    kept.add(raw);
                    frameworkEntryKept = true;
                } else {
                    skipped++;
                }
            } else {
                // 异常消息行 / Caused by / "... N more" 行, 保留, 并视为新的框架区间开始
                kept.add(raw);
                frameworkEntryKept = false;
            }
        }
        if (skipped > 0) {
            kept.add("\t... 已裁剪 " + skipped + " 个框架栈帧");
        }
        return String.join("\n", kept);
    }

    private boolean isBusinessFrame(String frame, List<String> businessPackages) {
        for (String prefix : businessPackages) {
            if (frame.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
