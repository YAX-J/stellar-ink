package com.stellarink.user.component;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.sharedmodel.auth.TokenRevocationKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/** 将当前 JWT 加入 Redis 撤销列表，记录只保留到令牌自然过期。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRevocationService {

    private final RedisUtils redisUtils;

    public void revokeCurrentToken() {
        String token = StpUtil.getTokenValue();
        if (!StringUtils.hasText(token)) {
            return;
        }
        long timeout = StpUtil.getTokenTimeout(token);
        String key = TokenRevocationKey.of(token);
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            redisUtils.set(key, true);
        } else if (timeout > 0) {
            redisUtils.set(key, true, Duration.ofSeconds(timeout));
        } else {
            return;
        }
        log.info("当前会话已撤销 userId={} remainingSeconds={}", StpUtil.getLoginIdDefaultNull(), timeout);
    }
}
