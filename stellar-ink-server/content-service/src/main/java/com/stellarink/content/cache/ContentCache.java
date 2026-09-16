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

/** 内容服务缓存键、有效期和命名空间版本的统一入口。 */
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

    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        return redisCache.getOrLoad(key, type, ttl, loader);
    }

    public <T> T getOrLoad(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
        return redisCache.getOrLoad(key, type, ttl, loader);
    }

    public <T> T get(String key, Class<T> type) {
        return redisCache.get(key, type);
    }

    public void put(String key, Object value, Duration ttl) {
        redisCache.put(key, value, ttl);
    }

    public void evict(String key) {
        redisCache.evict(key);
    }

    public void evictVersioned(String namespace, String category, Object... parts) {
        evict(versionedKey(namespace, category, parts));
    }

    public void invalidate(String namespace) {
        redisCache.invalidateVersion(VERSION_PREFIX + namespace);
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
