package com.stellarink.gateway.config;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SaTokenConfigureTest {

    private final SaTokenConfigure configure = new SaTokenConfigure();

    @Test
    void shouldUseStatelessJwtLogic() {
        assertThat(configure.getStpLogic()).isInstanceOf(StpLogicJwtForStateless.class);
    }

    @Test
    void shouldProtectPendingLinksBeforePublicGetRule() {
        assertThat(SaTokenConfigure.requiresAdminRead("GET", "/links/pending")).isTrue();
        assertThat(SaTokenConfigure.requiresAdminRead("GET", "/links")).isFalse();
        assertThat(SaTokenConfigure.requiresAdminRead("POST", "/links/pending")).isFalse();
    }

    @Test
    void shouldProtectAuthorReadEndpointsBeforePublicGetRule() {
        assertThat(SaTokenConfigure.requiresAuthorRead("GET", "/posts/mine")).isTrue();
        assertThat(SaTokenConfigure.requiresAuthorRead("GET", "/notes/mine")).isTrue();
        assertThat(SaTokenConfigure.requiresAuthorRead("GET", "/notes/review")).isTrue();
        assertThat(SaTokenConfigure.requiresAuthorRead("GET", "/notes")).isFalse();
        assertThat(SaTokenConfigure.requiresAuthorRead("POST", "/notes/review")).isFalse();
    }
}
