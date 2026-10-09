package com.stellarink.common.redis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCacheTest {

    private RedisUtils redisUtils;
    private SimpleMeterRegistry meterRegistry;
    private RedisCache redisCache;

    @BeforeEach
    void setUp() {
        redisUtils = mock(RedisUtils.class);
        meterRegistry = new SimpleMeterRegistry();
        redisCache = new RedisCache(redisUtils, meterRegistry);
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

    // ------------------------------------------------------------------ 指标
    // 旁路缓存的故障是静默的：Redis 挂掉时业务完全不报错，只是所有请求都回源。
    // 所以「缓存是不是好的」只能靠下面这几个数字回答，它们必须被断言。

    @Test
    @DisplayName("命中与未命中按命名空间分开计数")
    void hitAndMissAreCountedSeparately() {
        when(redisUtils.get("post:42", String.class)).thenReturn("星笺");
        redisCache.getOrLoad("post", "post:42", String.class, Duration.ofMinutes(1), () -> "数据库");

        when(redisUtils.get("post:43", String.class)).thenReturn(null);
        redisCache.getOrLoad("post", "post:43", String.class, Duration.ofMinutes(1), () -> "数据库");

        assertEquals(1d, requests("post", "hit"));
        assertEquals(1d, requests("post", "miss"));
    }

    @Test
    @DisplayName("出错记 error、熔断期内记 bypass，且两者绝不混为一谈")
    void errorAndBypassAreCountedSeparately() {
        when(redisUtils.get("post:42", String.class)).thenThrow(new IllegalStateException("redis down"));

        redisCache.get("post", "post:42", String.class);
        // 熔断窗口内：这次根本没访问 Redis
        redisCache.get("post", "post:43", String.class);

        assertEquals(1d, requests("post", "error"), "第一次是「Redis 出错」");
        assertEquals(1d, requests("post", "bypass"), "第二次是「熔断跳过」，不是「缓存里没有」");
        // 用 find 而不是 get：Micrometer 只在该计量器被自增过之后才创建它，
        // 「计数为 0」在这里的表现是「计量器不存在」，用 get 会抛 MeterNotFoundException。
        assertNull(meterRegistry.find("stellar.cache.requests")
                        .tag("namespace", "post").tag("result", "miss").counter(),
                "熔断跳过不该被算成未命中");
        assertEquals(1d, meterRegistry.get("stellar.cache.suspended").gauge().value(),
                "熔断状态必须能被看见 —— 这是缓存层唯一的静默故障信号");
    }

    @Test
    @DisplayName("版本号读取不计入命中率：它的「未命中」是正常状态（版本 0），且每个请求都发生一次")
    void versionReadIsNotCountedAsCacheRequest() {
        when(redisUtils.get("cache:post:version", Long.class)).thenReturn(null);

        assertEquals(0L, redisCache.version("cache:post:version"));

        assertTrue(meterRegistry.find("stellar.cache.requests").counters().isEmpty(),
                "把版本读取计进去会永久拉低整体命中率，看板上的数字就再也反映不了真实缓存效果");
    }

    @Test
    @DisplayName("版本推进与精确删除按命名空间计数（用来解释「命中率为什么突然掉了」）")
    void invalidationsAndEvictionsAreCounted() {
        redisCache.invalidateVersion("post", "cache:post:version");
        redisCache.evict("post", "post:42");

        assertEquals(1d, meterRegistry.get("stellar.cache.invalidations")
                .tag("namespace", "post").counter().count());
        assertEquals(1d, meterRegistry.get("stellar.cache.evictions")
                .tag("namespace", "post").counter().count());
    }

    private double requests(String namespace, String result) {
        return meterRegistry.get("stellar.cache.requests")
                .tag("namespace", namespace)
                .tag("result", result)
                .counter()
                .count();
    }
}
