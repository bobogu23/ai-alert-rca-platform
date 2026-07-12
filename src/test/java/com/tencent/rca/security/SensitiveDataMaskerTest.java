package com.tencent.rca.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SensitiveDataMasker 单元测试: 校验常见敏感模式的脱敏覆盖.
 */
class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void shouldReturnNullOrEmptyAsIs() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }

    @Test
    void shouldMaskPhoneNumber() {
        String masked = masker.mask("用户手机号 13800138000 登录失败");
        assertThat(masked).contains("<phone>").doesNotContain("13800138000");
    }

    @Test
    void shouldMaskIdCardAndEmail() {
        String masked = masker.mask("身份证 11010119900307461X 邮箱 abc@test.com");
        assertThat(masked).contains("<id-card>");
        assertThat(masked).contains("<email>");
        assertThat(masked).doesNotContain("11010119900307461X");
        assertThat(masked).doesNotContain("abc@test.com");
    }

    @Test
    void shouldMaskSecretButKeepKeyName() {
        String masked = masker.mask("token=abc123secretvalue");
        assertThat(masked).contains("token");
        assertThat(masked).contains("<secret>");
        assertThat(masked).doesNotContain("abc123secretvalue");
    }

    @Test
    void shouldMaskBankCard() {
        String masked = masker.mask("卡号 6222021234567890123 交易异常");
        assertThat(masked).contains("<bank-card>").doesNotContain("6222021234567890123");
    }
}
