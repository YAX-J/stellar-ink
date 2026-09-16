package com.stellarink.common.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisUtilsTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisUtils redisUtils;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        redisUtils = new RedisUtils(redisTemplate, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    @DisplayName("对象按 JSON 写入并携带有效期")
    void setJsonWithTtl() {
        Duration ttl = Duration.ofMinutes(10);

        redisUtils.set("user:1", new SampleValue(1L, "星笺"), ttl);

        verify(valueOperations).set("user:1", "{\"id\":1,\"name\":\"星笺\"}", ttl);
    }

    @Test
    @DisplayName("支持按具体类型和泛型类型读取")
    void getJsonValue() {
        when(valueOperations.get("user:1")).thenReturn("{\"id\":1,\"name\":\"星笺\"}");
        when(valueOperations.get("tags:1")).thenReturn("[\"Java\",\"Redis\"]");

        assertEquals(new SampleValue(1L, "星笺"), redisUtils.get("user:1", SampleValue.class));
        assertEquals(List.of("Java", "Redis"),
                redisUtils.get("tags:1", new TypeReference<List<String>>() { }));
    }

    @Test
    @DisplayName("键不存在时读取返回 null")
    void missingKeyReturnsNull() {
        when(valueOperations.get("missing")).thenReturn(null);

        assertNull(redisUtils.get("missing", SampleValue.class));
    }

    @Test
    @DisplayName("删除、存在判断、过期和计数保留 Redis 返回语义")
    void basicOperations() {
        Duration ttl = Duration.ofSeconds(30);
        when(redisTemplate.delete("key")).thenReturn(true);
        when(redisTemplate.hasKey("key")).thenReturn(false);
        when(redisTemplate.expire("key", ttl)).thenReturn(true);
        when(redisTemplate.getExpire("key", TimeUnit.SECONDS)).thenReturn(18L);
        when(valueOperations.increment("counter", 2L)).thenReturn(7L);

        assertTrue(redisUtils.delete("key"));
        assertFalse(redisUtils.hasKey("key"));
        assertTrue(redisUtils.expire("key", ttl));
        assertEquals(18L, redisUtils.getExpire("key"));
        assertEquals(7L, redisUtils.increment("counter", 2L));
    }

    @Test
    @DisplayName("空键、空值和非正有效期会被拒绝")
    void rejectInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> redisUtils.set(" ", "value"));
        assertThrows(NullPointerException.class, () -> redisUtils.set("key", null));
        assertThrows(IllegalArgumentException.class,
                () -> redisUtils.set("key", "value", Duration.ZERO));
    }

    private record SampleValue(Long id, String name) {
    }
}
