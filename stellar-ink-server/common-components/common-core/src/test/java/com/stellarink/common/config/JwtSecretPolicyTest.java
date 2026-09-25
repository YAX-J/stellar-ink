package com.stellarink.common.config;

import com.stellarink.sharedmodel.auth.JwtSecretPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密钥规则的单测。放在 common-core 而不是 shared-model：后者是纯模型模块、没有测试基础设施，
 * 而 common-core 依赖它，同样能覆盖到。
 */
class JwtSecretPolicyTest {

    /** 一个合规的 48 字符密钥（openssl rand -base64 48 的形态） */
    private static final String GOOD =
            "5nQx8vT2mKpR7cWj4bYzL1sHdGfAeUoiNqXtCvBmMjKlPoIuYtReWqAzXsDcFgH";

    @Test
    @DisplayName("档位判定：只有全部 profile 都是 dev 系才放行")
    void shouldOnlySkipCheckForPureDevProfiles() {
        assertThat(JwtSecretPolicy.requiresCheck(new String[]{"dev"})).isFalse();
        assertThat(JwtSecretPolicy.requiresCheck(new String[]{"DEV"})).isFalse();
        assertThat(JwtSecretPolicy.requiresCheck(new String[]{"dev-local"})).isFalse();

        // 空 / null = 未指定档位：从严校验，不能因为「没写档位」就放行
        assertThat(JwtSecretPolicy.requiresCheck(new String[0])).isTrue();
        assertThat(JwtSecretPolicy.requiresCheck(null)).isTrue();

        assertThat(JwtSecretPolicy.requiresCheck(new String[]{"test"})).isTrue();
        assertThat(JwtSecretPolicy.requiresCheck(new String[]{"prod"})).isTrue();
        assertThat(JwtSecretPolicy.requiresCheck(new String[]{"staging"})).isTrue();
        // 混着 dev 与非 dev：必须校验（allMatch 而非 noneMatch 的原因）
        assertThat(JwtSecretPolicy.requiresCheck(new String[]{"dev", "prod"})).isTrue();
    }

    @Test
    @DisplayName("未解析的占位符字面量必须被拦：它长度够，长度规则放它过去")
    void shouldRejectUnresolvedPlaceholderLiteral() {
        String literal = "${SA_TOKEN_JWT_SECRET}";
        assertThat(literal.length()).isGreaterThanOrEqualTo(20);   // 说明只靠长度拦不住
        assertThat(JwtSecretPolicy.violation(literal)).contains("公开可见");
        assertThat(JwtSecretPolicy.violation(literal)).contains("根本没注入");
    }

    @Test
    @DisplayName("空值 / 仓库默认值 / 官方示例值 / 过短 一律违规")
    void shouldRejectKnownBadSecrets() {
        assertThat(JwtSecretPolicy.violation(null)).contains("未设置");
        assertThat(JwtSecretPolicy.violation("")).contains("未设置");
        assertThat(JwtSecretPolicy.violation("   ")).contains("未设置");
        assertThat(JwtSecretPolicy.violation("stellar-ink-satoken-jwt-secret-32bytes")).contains("公开可见");
        assertThat(JwtSecretPolicy.violation("changeme")).contains("公开可见");
        assertThat(JwtSecretPolicy.violation("CHANGE_ME")).contains("公开可见");
        assertThat(JwtSecretPolicy.violation("short-secret")).contains("长度不足");
    }

    @Test
    @DisplayName("合规密钥（足够长且不在禁止名单）放行")
    void shouldAcceptGoodSecret() {
        assertThat(JwtSecretPolicy.violation(GOOD)).isNull();
    }
}
