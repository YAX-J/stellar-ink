package com.stellarink.gateway.config;

import com.stellarink.sharedmodel.auth.JwtSecretPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关侧启动哨兵：非 dev 档缺密钥 / 用公开可见的密钥时必须拒绝启动。
 *
 * <p>为什么网关必须有这一层：它自己用 {@code StpUtil.checkLogin()} 验签、并以 JWT 里的
 * {@code role} 作门槛依据，密钥一旦是公开可见的字面量，任何人都能签一个 {@code role=ADMIN}
 * 的 token 直接穿过网关。而网关不依赖 common-core，拿不到那边的 {@code SecretGuard}。
 */
class GatewaySecretGuardTest {

    private static final String GOOD =
            "5nQx8vT2mKpR7cWj4bYzL1sHdGfAeUoiNqXtCvBmMjKlPoIuYtReWqAzXsDcFgH";

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    @DisplayName("dev 档放行（本机联调），其余档位一律校验")
    void shouldAllowOnlyDevProfile() {
        assertThatCode(() -> new GatewaySecretGuard(env("dev"), "stellar-ink-satoken-jwt-secret-32bytes").verify())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("test 档拦下未解析的占位符字面量（这正是缺环境变量时的实际取值）")
    void shouldRejectPlaceholderLiteralInTestProfile() {
        assertThatThrownBy(() -> new GatewaySecretGuard(env("test"), "${SA_TOKEN_JWT_SECRET}").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("公开可见")
                .hasMessageContaining("SA_TOKEN_JWT_SECRET");   // 消息里给出可操作指引
    }

    @Test
    @DisplayName("prod 档拦下空值与过短密钥，合规密钥放行")
    void shouldRejectBadSecretsAndAcceptGoodOneInProd() {
        assertThatThrownBy(() -> new GatewaySecretGuard(env("prod"), "").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未设置");
        assertThatThrownBy(() -> new GatewaySecretGuard(env("prod"), "short").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度不足");
        assertThatThrownBy(() -> new GatewaySecretGuard(env("prod"), "changeme").verify())
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new GatewaySecretGuard(env("prod"), GOOD).verify())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("未指定档位同样从严（网关绝不能因为「没写 profile」而放行）")
    void shouldRejectWhenNoProfileActive() {
        assertThatThrownBy(() -> new GatewaySecretGuard(env(), "${SA_TOKEN_JWT_SECRET}").verify())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("dev 与非 dev 混用时必须校验（别让 dev 把 prod 一起放行）")
    void shouldStillCheckWhenDevIsMixedWithNonDev() {
        assertThatThrownBy(() -> new GatewaySecretGuard(env("dev", "prod"), "${SA_TOKEN_JWT_SECRET}").verify())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("规则复用共享实现：规则类与哨兵判定一致")
    void shouldSharePolicyWithCommonCore() {
        assertThatCode(() -> new GatewaySecretGuard(env("staging"), GOOD).verify()).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(JwtSecretPolicy.FORBIDDEN_SECRETS)
                .contains("${SA_TOKEN_JWT_SECRET}");
    }
}
