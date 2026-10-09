package com.stellarink.content.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stellarink.common.redis.RedisCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 内容服务缓存键、有效期和命名空间版本的统一入口。
 *
 * <p><b>为什么每个读写方法都要传命名空间</b>：它有两个用途，缺一不可 ——
 * <ul>
 *   <li><b>失效</b>：{@link #invalidate(String)} 推进某个命名空间的版本号，
 *       一次 INCR 让该领域下**所有参数组合**的旧缓存立即不可达（不必去 KEYS 扫描）；</li>
 *   <li><b>指标</b>：{@code stellar.cache.requests{namespace=...}} 按领域切分命中率。
 *       没有这个标签就只能看到「总命中率」，而「post 列表命中率只有 5%」
 *       这种具体问题会被其它领域的好数字平均掉。</li>
 * </ul>
 * 命名空间取值就是各领域的直接名称：{@code post} / {@code note} / {@code comment} /
 * {@code tag} / {@code stats} / {@code meteor} / {@code echo} / {@code link}。
 */
@Component
@RequiredArgsConstructor
public class ContentCache {

    public static final Duration SHORT_TTL = Duration.ofSeconds(30);
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(1);
    public static final Duration LONG_TTL = Duration.ofMinutes(5);

    private static final String PREFIX = "stellar-ink:content:cache:";
    private static final String VERSION_PREFIX = PREFIX + "version:";

    private final RedisCache redisCache;

    public String key(String namespace, String category, Object... parts) {
        return PREFIX + namespace + ":" + category + ":" + fingerprint(parts);
    }

    public String versionedKey(String namespace, String category, Object... parts) {
        long version = redisCache.version(VERSION_PREFIX + namespace);
        return PREFIX + namespace + ":" + category + ":v" + version + ":" + fingerprint(parts);
    }

    public <T> T getOrLoad(String namespace, String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        return redisCache.getOrLoad(namespace, key, type, ttl, loader);
    }

    public <T> T getOrLoad(String namespace, String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
        return redisCache.getOrLoad(namespace, key, type, ttl, loader);
    }

    public <T> T get(String namespace, String key, Class<T> type) {
        return redisCache.get(namespace, key, type);
    }

    public void put(String namespace, String key, Object value, Duration ttl) {
        redisCache.put(namespace, key, value, ttl);
    }

    public void evict(String namespace, String key) {
        redisCache.evict(namespace, key);
    }

    /**
     * 精确失效某一条详情缓存（按 id）。
     *
     * <p>为什么不用 {@link #invalidate(String)}：浏览计数这类高频写操作每次都会改一条详情，
     * 整体推进版本号会把该领域下**所有**列表与详情缓存一起作废 —— 代价远大于清掉那一条。
     */
    public void evictVersioned(String namespace, String category, Object... parts) {
        evict(namespace, versionedKey(namespace, category, parts));
    }

    public void invalidate(String namespace) {
        redisCache.invalidateVersion(namespace, VERSION_PREFIX + namespace);
    }

    private String fingerprint(Object... parts) {
        StringBuilder source = new StringBuilder();
        if (parts != null) {
            for (Object part : parts) {
                source.append(part == null ? "<null>" : part).append('\u001f');
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", ex);
        }
    }
}
