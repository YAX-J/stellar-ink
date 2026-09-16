package com.stellarink.common.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Redis 通用 Value 操作工具，键使用字符串，值统一存为 JSON。
 *
 * <p>本工具不承载业务键规则、缓存策略、分布式锁或限流逻辑；业务方应在各自领域内定义键名与失效时间。
 */
@Component
public class RedisUtils {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisUtils(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入永久有效的 JSON 值。
     */
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(requireKey(key), serialize(value));
    }

    /**
     * 写入带有效期的 JSON 值。
     */
    public void set(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(requireKey(key), serialize(value), requirePositiveTtl(ttl));
    }

    /**
     * 按具体类型读取 JSON 值；键不存在时返回 {@code null}。
     */
    public <T> T get(String key, Class<T> type) {
        Objects.requireNonNull(type, "Redis 目标类型不能为空");
        String value = redisTemplate.opsForValue().get(requireKey(key));
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Redis 值反序列化失败，key=" + key, ex);
        }
    }

    /**
     * 按泛型类型读取 JSON 值；键不存在时返回 {@code null}。
     */
    public <T> T get(String key, TypeReference<T> type) {
        Objects.requireNonNull(type, "Redis 目标类型不能为空");
        String value = redisTemplate.opsForValue().get(requireKey(key));
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Redis 值反序列化失败，key=" + key, ex);
        }
    }

    /**
     * 删除键。
     *
     * @return 键原先存在且已删除时返回 true
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(requireKey(key)));
    }

    /**
     * 判断键是否存在。
     */
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(requireKey(key)));
    }

    /**
     * 为已有键设置有效期。
     *
     * @return 设置成功时返回 true，键不存在时返回 false
     */
    public boolean expire(String key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.expire(requireKey(key), requirePositiveTtl(ttl)));
    }

    /**
     * 查询剩余有效期，单位为秒。
     *
     * @return 非负数表示剩余秒数，-1 表示永久有效，-2 表示键不存在
     */
    public long getExpire(String key) {
        Long seconds = redisTemplate.getExpire(requireKey(key), TimeUnit.SECONDS);
        return seconds == null ? -2 : seconds;
    }

    /**
     * 原子增加整数计数器。计数器使用 Redis 原生数字字符串，不经过 JSON 对象序列化。
     */
    public long increment(String key, long delta) {
        Long result = redisTemplate.opsForValue().increment(requireKey(key), delta);
        if (result == null) {
            throw new IllegalStateException("Redis 计数器自增未返回结果，key=" + key);
        }
        return result;
    }

    private String serialize(Object value) {
        Objects.requireNonNull(value, "Redis 值不能为空，需要清除时请调用 delete");
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Redis 值序列化失败", ex);
        }
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Redis 键不能为空");
        }
        return key;
    }

    private Duration requirePositiveTtl(Duration ttl) {
        Objects.requireNonNull(ttl, "Redis 有效期不能为空");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Redis 有效期必须大于 0");
        }
        return ttl;
    }
}
