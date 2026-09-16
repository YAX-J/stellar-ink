package com.stellarink.user.service.impl;

import com.stellarink.common.redis.RedisCache;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.sharedmodel.dto.user.LoginDTO;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.user.UserVO;
import com.stellarink.user.component.AvatarStorage;
import com.stellarink.user.component.TokenRevocationService;
import com.stellarink.user.mapper.UserMapper;
import com.stellarink.user.pojo.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final String FAILURE_KEY = "stellar-ink:user:login:failure:stellar";
    private static final String LOCK_KEY = "stellar-ink:user:login:lock:stellar";
    private static final Duration WINDOW = Duration.ofMinutes(15);

    @Mock
    private UserMapper userMapper;
    @Mock
    private BCryptPasswordEncoder passwordEncoder;
    @Mock
    private AvatarStorage avatarStorage;
    @Mock
    private RedisUtils redisUtils;
    @Mock
    private TokenRevocationService tokenRevocationService;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(
                userMapper, passwordEncoder, avatarStorage, tokenRevocationService,
                redisUtils, new RedisCache(redisUtils));
    }

    @Test
    @DisplayName("账号已锁定时不查询数据库也不执行 BCrypt")
    void lockedAccountIsRejectedBeforePasswordCheck() {
        when(redisUtils.hasKey(LOCK_KEY)).thenReturn(true);
        when(redisUtils.getExpire(LOCK_KEY)).thenReturn(601L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.login(login("stellar", "wrong")));

        assertEquals(403, exception.getCode());
        verifyNoInteractions(userMapper, passwordEncoder);
    }

    @Test
    @DisplayName("锁键在检查期间过期时继续正常登录流程")
    void expiredLockRaceDoesNotRejectAccount() {
        when(redisUtils.hasKey(LOCK_KEY)).thenReturn(true);
        when(redisUtils.getExpire(LOCK_KEY)).thenReturn(-2L);
        when(userMapper.selectOne(any())).thenReturn(null);
        when(redisUtils.increment(FAILURE_KEY, 1L, WINDOW)).thenReturn(1L);
        when(redisUtils.get(FAILURE_KEY, Integer.class)).thenReturn(1);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.login(login("stellar", "wrong")));

        assertEquals(401, exception.getCode());
        verify(userMapper).selectOne(any());
    }

    @Test
    @DisplayName("普通登录失败写入 Redis 失败窗口并返回未授权")
    void failedLoginUsesRedisWindow() {
        when(redisUtils.hasKey(LOCK_KEY)).thenReturn(false);
        when(userMapper.selectOne(any())).thenReturn(null);
        when(redisUtils.increment(FAILURE_KEY, 1L, WINDOW)).thenReturn(2L);
        when(redisUtils.get(FAILURE_KEY, Integer.class)).thenReturn(2);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.login(login("stellar", "wrong")));

        assertEquals(401, exception.getCode());
        verify(redisUtils).increment(FAILURE_KEY, 1L, WINDOW);
        verify(redisUtils).get(FAILURE_KEY, Integer.class);
        verify(redisUtils, never()).set(eq(LOCK_KEY), any(), eq(WINDOW));
    }

    @Test
    @DisplayName("第五次失败创建 Redis 锁定键并清理失败计数")
    void fifthFailureLocksAccount() {
        when(redisUtils.hasKey(LOCK_KEY)).thenReturn(false);
        when(userMapper.selectOne(any())).thenReturn(null);
        when(redisUtils.increment(FAILURE_KEY, 1L, WINDOW)).thenReturn(5L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.login(login("stellar", "wrong")));

        assertEquals(403, exception.getCode());
        verify(redisUtils).set(LOCK_KEY, 1, WINDOW);
        verify(redisUtils).delete(FAILURE_KEY);
        verify(redisUtils, never()).get(FAILURE_KEY, Integer.class);
    }

    @Test
    @DisplayName("用户资料命中 Redis 时不再查询数据库")
    void profileUsesRedisCache() {
        UserVO cached = new UserVO();
        cached.setId(7L);
        cached.setNickname("缓存星籍");
        when(redisUtils.get("stellar-ink:user:cache:profile:7", UserVO.class)).thenReturn(cached);

        UserVO profile = userService.profile(7L);

        assertEquals("缓存星籍", profile.getNickname());
        verifyNoInteractions(userMapper);
    }

    private LoginDTO login(String username, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }
}
