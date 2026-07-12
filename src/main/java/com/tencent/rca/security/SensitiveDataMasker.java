package com.tencent.rca.security;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 敏感数据脱敏器 (详细设计文档 8.3).
 * 进入 LLM 上下文前对文本做确定性正则脱敏, 覆盖手机号、身份证、邮箱、密钥/token 等常见敏感模式.
 * 与 LogFingerprintService 归一化规则风格一致, 不依赖 LLM, 规则可扩展.
 */
@Component
public class SensitiveDataMasker {

    /** 中国大陆手机号. */
    private static final Pattern PHONE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    /** 18 位身份证号. */
    private static final Pattern ID_CARD = Pattern.compile("\\b\\d{17}[0-9Xx]\\b");
    /** 邮箱地址. */
    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    /** 银行卡号(13-19 位连续数字). */
    private static final Pattern BANK_CARD = Pattern.compile("\\b\\d{13,19}\\b");
    /** 密钥/token 键值, 如 token=xxx、apikey: xxx、secret "xxx". */
    private static final Pattern SECRET_KV = Pattern.compile(
            "(?i)(token|api[_-]?key|secret|password|passwd|access[_-]?key)([\"'=:\\s]+)([^\\s\"',]+)");

    /**
     * 对文本做脱敏.
     *
     * @param text 原始文本 (可为空)
     * @return 脱敏后的文本
     */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        // 先脱敏密钥键值(保留键名, 仅遮蔽值), 再处理各类高基数敏感串
        result = SECRET_KV.matcher(result).replaceAll("$1$2<secret>");
        result = EMAIL.matcher(result).replaceAll("<email>");
        result = ID_CARD.matcher(result).replaceAll("<id-card>");
        result = PHONE.matcher(result).replaceAll("<phone>");
        result = BANK_CARD.matcher(result).replaceAll("<bank-card>");
        return result;
    }
}
