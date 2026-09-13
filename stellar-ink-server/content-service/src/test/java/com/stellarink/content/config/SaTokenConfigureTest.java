package com.stellarink.content.config;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SaTokenConfigureTest {

    @Test
    void shouldUseStatelessJwtLogic() {
        assertThat(new SaTokenConfigure().getStpLogic())
                .isInstanceOf(StpLogicJwtForStateless.class);
    }
}
