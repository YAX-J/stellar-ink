package com.stellarink.common.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 基于 {@link RedisUtils} 的旁路缓存封装。
 *
 * <p>缓存读取、写入和失效均采用故障放行策略：Redis 短暂不可用时记录告警并回源，
 * 不让非关键缓存故障中断业务请求。登录锁定等安全状态仍应直接使用 {@link RedisUtils}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCache {

    private static final long FAILURE_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final RedisUtils redisUtils;
    private final AtomicLong suspendedUntilNanos = new AtomicLong();

    public <T> T get(String key, Class<T> type) {
        if (isSuspended()) {
            return null;
        }
        try {
            T value = redisUtils.get(key, type);
            resume();
            return value;
        } catch (RuntimeException ex) {
            suspend();
            log.warn("读取 Redis 缓存失败，回源业务数据 key={} error={}", key, ex.toString());
            return null;
        }
    }

    public <T> T get(String key, TypeReference<T> type) {
        if (isSuspended()) {
            return null;
        }
        try {
            T value = redisUtils.get(key, type);
            resume();
            return value;
        } catch (RuntimeException ex) {
            suspend();
            log.warn("读取 Redis 缓存失败，回源业务数据 key={} error={}", key, ex.toString());
            return null;
        }
    }

    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        T cached = get(key, type);
        if (cached != null) {
            return cached;
        }
        return loadAndCache(key, ttl, loader);
    }

    public <T> T getOrLoad(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
        T cached = get(key, type);
        if (cached != null) {
            return cached;
        }
        return loadAndCache(key, ttl, loader);
    }

    public void put(String key, Object value, Duration ttl) {
        if (value == null || isSuspended()) {
            return;
        }
        try {
            redisUtils.set(key, value, ttl);
            resume();
        } catch (RuntimeException ex) {
            suspend();
            log.warn("写入 Redis 缓存失败，忽略本次缓存 key={} error={}", key, ex.toString());
        }
    }

    public void evict(String key) {
        if (isSuspended()) {
            return;
        }
        try {
            redisUtils.delete(key);
            resume();
        } catch (RuntimeException ex) {
            suspend();
            log.warn("清理 Redis 缓存失败，等待有效期自然淘汰 key={} error={}", key, ex.toString());
        }
    }

    /**
     * 读取缓存命名空间版本。版本键不存在时使用 0，不主动创建无意义的键。
     */
    public long version(String versionKey) {
        Long version = get(versionKey, Long.class);
        return version == null ? 0L : version;
    }

    /**
     * 原子推进命名空间版本，使旧版本下的任意参数缓存立即不可达。
     */
    public void invalidateVersion(String versionKey) {
        if (isSuspended()) {
            return;
        }
        try {
            redisUtils.increment(versionKey, 1L);
            resume();
        } catch (RuntimeException ex) {
            suspend();
            log.warn("推进 Redis 缓存版本失败，旧值最多保留到有效期结束 key={} error={}",
                    versionKey, ex.toString());
        }
    }

    private <T> T loadAndCache(String key, Duration ttl, Supplier<T> loader) {
        Objects.requireNonNull(loader, "缓存回源函数不能为空");
        T loaded = loader.get();
        put(key, loaded, ttl);
        return loaded;
    }

    private boolean isSuspended() {
        return System.nanoTime() < suspendedUntilNanos.get();
    }

    private void suspend() {
        suspendedUntilNanos.set(System.nanoTime() + FAILURE_BACKOFF_NANOS);
    }

    private void resume() {
        suspendedUntilNanos.set(0L);
    }
}
