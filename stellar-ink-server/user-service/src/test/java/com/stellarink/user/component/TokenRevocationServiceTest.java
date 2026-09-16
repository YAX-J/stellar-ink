package com.stellarink.user.component;

import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.sharedmodel.auth.TokenRevocationKey;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TokenRevocationServiceTest {

    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final TokenRevocationService service = new TokenRevocationService(redisUtils);

    @Test
    void storesOnlyTokenDigestUntilNaturalExpiry() {
        String tokenValue = "signed.jwt.value";
        try (MockedStatic<StpUtil> token = Mockito.mockStatic(StpUtil.class)) {
            token.when(StpUtil::getTokenValue).thenReturn(tokenValue);
            token.when(() -> StpUtil.getTokenTimeout(tokenValue)).thenReturn(3600L);
            token.when(StpUtil::getLoginIdDefaultNull).thenReturn(7L);

            service.revokeCurrentToken();
        }

        verify(redisUtils).set(TokenRevocationKey.of(tokenValue), true, Duration.ofHours(1));
    }
}
