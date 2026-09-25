package com.stellarink.common.config;

import com.stellarink.sharedmodel.auth.JwtSecretPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SecretGuard} 的启动行为：dev 放行、test/prod 拦截、不验签的服务跳过。
 *
 * <p>这一组用例的存在本身就是回归保护：曾经（2026-09 之前）只有 prod 被校验，
 * 且没人发现「配置里不写默认值」并不等于启动失败 —— 测试环境因此可以用
 * 字面量 {@code ${SA_TOKEN_JWT_SECRET}} 当密钥静默跑起来。
 */
class SecretGuardTest {

    private static final String GOOD =
            "5nQx8vT2mKpR7cWj4bYzL1sHdGfAeUoiNqXtCvBmMjKlPoIuYtReWqAzXsDcFgH";

    /** 构造一个「声明了密钥属性」的环境 */
    private static MockEnvironment envWithProperty(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        environment.setProperty(JwtSecretPolicy.PROPERTY, "placeholder-for-containsProperty");
        return environment;
    }

    @Test
    @DisplayName("未声明该属性的服务不参与验签，跳过校验（不能用密钥存在性去误伤它们）")
    void shouldSkipServicesWithoutTheProperty() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");   // 即便是 prod，没声明属性就跳过

        assertThatCode(() -> new SecretGuard(environment, "").verify()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dev 档放行仓库默认密钥（本机联调要开箱即用）")
    void shouldAllowDevProfile() {
        MockEnvironment environment = envWithProperty("dev");

        assertThatCode(() -> new SecretGuard(environment, "stellar-ink-satoken-jwt-secret-32bytes").verify())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("test 档必须拦截：字面量占位符 / 空 / 过短 / 仓库默认值")
    void shouldRejectTestProfile() {
        assertThatThrownBy(() -> new SecretGuard(envWithProperty("test"), "${SA_TOKEN_JWT_SECRET}").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("公开可见");
        assertThatThrownBy(() -> new SecretGuard(envWithProperty("test"), "").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未设置");
        assertThatThrownBy(() -> new SecretGuard(envWithProperty("test"), "too-short").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度不足");
        assertThatThrownBy(() -> new SecretGuard(envWithProperty("test"), "changeme").verify())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("prod 档同上；合规密钥放行")
    void shouldRejectProdProfileButAcceptGoodSecret() {
        assertThatThrownBy(() -> new SecretGuard(envWithProperty("prod"), "${SA_TOKEN_JWT_SECRET}").verify())
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new SecretGuard(envWithProperty("prod"), GOOD).verify())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("未指定档位时从严：不因为是「没写 profile」就放行")
    void shouldRejectWhenNoProfileIsActive() {
        assertThatThrownBy(() -> new SecretGuard(envWithProperty(), "${SA_TOKEN_JWT_SECRET}").verify())
                .isInstanceOf(IllegalStateException.class);
    }
}
