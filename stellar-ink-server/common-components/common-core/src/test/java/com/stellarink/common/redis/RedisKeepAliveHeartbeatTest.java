package com.stellarink.common.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保活心跳的回归测试：它是「一段时间不操作就 503」唯一的解。
 *
 * <p>为什么不能只靠连接池校验：Lettuce 的池化工厂 `validateObject()` 只是
 * `StatefulConnection.isOpen()`（本地标志位），被 NAT 静默丢弃的半开连接照样返回 true ——
 * 所以必须有**真发命令**的心跳，失败时还要把整池丢掉（否则坏连接会一直躺在池里被借出去）。
 */
class RedisKeepAliveHeartbeatTest {

    @Test
    @DisplayName("心跳真的发 PING（链路不空闲，NAT 就不会丢映射）")
    void heartbeatPingsTheConnection() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);

        RedisKeepAliveHeartbeat heartbeat = new RedisKeepAliveHeartbeat(factory, Duration.ofSeconds(30));
        heartbeat.beat();
        heartbeat.beat();

        verify(connection, times(2)).ping();
        // 借出来的连接必须关掉（归还池子），否则每 30s 泄漏一条
        verify(connection, times(2)).close();
    }

    @Test
    @DisplayName("心跳失败要丢掉整池旧连接（半开连接只有重建才能摆脱）")
    void heartbeatResetsPoolWhenPingFails() {
        LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenThrow(new IllegalStateException("Redis command timed out"));

        new RedisKeepAliveHeartbeat(factory, Duration.ofSeconds(30)).beat();

        verify(factory).resetConnection();
    }

    @Test
    @DisplayName("生命周期：start 起线程、stop 停线程，且是守护线程（不阻止 JVM 退出）")
    void lifecycleStartsAndStopsTheScheduler() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(mock(RedisConnection.class));

        RedisKeepAliveHeartbeat heartbeat = new RedisKeepAliveHeartbeat(factory, Duration.ofMillis(100));
        assertFalse(heartbeat.isRunning());
        heartbeat.start();
        assertTrue(heartbeat.isRunning());
        // start 是幂等的：重复调用不该起第二个线程
        heartbeat.start();
        assertTrue(heartbeat.isRunning());
        heartbeat.stop();
        assertFalse(heartbeat.isRunning());
    }

    @Test
    @DisplayName("心跳线程是守护线程（名字固定，排障时能在日志里认出来）")
    void heartbeatThreadIsDaemon() throws Exception {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(mock(RedisConnection.class));
        RedisKeepAliveHeartbeat heartbeat = new RedisKeepAliveHeartbeat(factory, Duration.ofMillis(50));
        heartbeat.start();
        try {
            Thread thread = Thread.getAllStackTraces().keySet().stream()
                    .filter(item -> "redis-keepalive".equals(item.getName()))
                    .findFirst().orElseThrow();
            assertTrue(thread.isDaemon());
        } finally {
            heartbeat.stop();
        }
    }
}
