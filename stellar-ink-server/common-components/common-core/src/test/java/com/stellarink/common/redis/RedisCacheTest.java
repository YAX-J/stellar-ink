package com.stellarink.common.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCacheTest {

    private RedisUtils redisUtils;
    private RedisCache redisCache;

    @BeforeEach
    void setUp() {
        redisUtils = mock(RedisUtils.class);
        redisCache = new RedisCache(redisUtils);
    }

    @Test
    @DisplayName("缓存命中时不执行回源函数")
    void hitSkipsLoader() {
        when(redisUtils.get("post:42", String.class)).thenReturn("星笺");

        String value = redisCache.getOrLoad("post:42", String.class, Duration.ofMinutes(1),
                () -> {
                    throw new AssertionError("缓存命中后不应回源");
                });

        assertEquals("星笺", value);
    }

    @Test
    @DisplayName("Redis 读取失败时回源数据库并跳过本次缓存写入")
    void redisFailureFallsBackToLoader() {
        when(redisUtils.get("post:42", String.class)).thenThrow(new IllegalStateException("redis down"));

        String value = redisCache.getOrLoad("post:42", String.class, Duration.ofMinutes(1),
                () -> "来自数据库");

        assertEquals("来自数据库", value);
        verify(redisUtils, never()).set(anyString(), any(), any());
    }

    @Test
    @DisplayName("Redis 失败后短暂熔断，避免同一请求重复等待超时")
    void redisFailureTemporarilySuspendsFollowingOperations() {
        when(redisUtils.get("post:42", String.class)).thenThrow(new IllegalStateException("redis down"));

        assertNull(redisCache.get("post:42", String.class));
        assertNull(redisCache.get("post:43", String.class));
        redisCache.evict("post:43");

        verify(redisUtils).get("post:42", String.class);
        verify(redisUtils, never()).get("post:43", String.class);
        verify(redisUtils, never()).delete("post:43");
    }

    @Test
    @DisplayName("命名空间失效使用 Redis 原子版本号")
    void invalidationAdvancesVersion() {
        redisCache.invalidateVersion("cache:post:version");

        verify(redisUtils).increment("cache:post:version", 1L);
    }
}
