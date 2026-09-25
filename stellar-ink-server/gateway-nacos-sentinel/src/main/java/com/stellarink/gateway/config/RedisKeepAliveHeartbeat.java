package com.stellarink.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redis 连接保活心跳（网关版）。
 *
 * <p>与 {@code com.stellarink.common.redis.RedisKeepAliveHeartbeat} 是同一套逻辑的另一份实现：
 * 网关是 WebFlux，不能依赖带 servlet 的 common-core。<b>改动要同步两处</b>。
 *
 * <p>为什么网关尤其需要它：撤销校验是 **fail-closed**，所以一条被 NAT 静默丢弃的空闲连接
 * 会直接变成「全站带 token 的请求 503」——用户看到的就是「一段时间不操作就报 503」。
 * 实测：服务重启 11 分钟后，多个请求同时撞上 {@code Redis command timed out}，
 * 重试也只是换了另一条同样死掉的池化连接（日志里「两次都失败」）。
 *
 * <p>心跳每 {@code stellar.ink.redis.keepalive.interval}（默认 30s）借一条连接发一次真 PING：
 * 链路永远不空闲 → NAT 不会丢映射；借到的那条被真实验证；一旦失败就丢掉整池、
 * 让下一条请求重建。心跳线程是守护线程，且最后启动、最先停止。
 */
@Slf4j
public class RedisKeepAliveHeartbeat implements SmartLifecycle {

    private final Duration interval;
    private final RedisConnectionFactory connectionFactory;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "redis-keepalive");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running;
    private volatile boolean failing;

    public RedisKeepAliveHeartbeat(RedisConnectionFactory connectionFactory, Duration interval) {
        this.connectionFactory = connectionFactory;
        this.interval = interval;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        long millis = Math.max(1000L, interval.toMillis());
        scheduler.scheduleWithFixedDelay(this::beat, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Redis 保活心跳已启动：每 {} ms 一次（撤销校验 fail-closed，连接不能闲死）", millis);
    }

    @Override
    public void stop() {
        running = false;
        scheduler.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /** 心跳本体。包级可见：测试直接调它，不必等 30 秒 */
    void beat() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.ping();
            if (failing) {
                failing = false;
                log.info("Redis 心跳恢复正常");
            }
        } catch (Exception error) {
            boolean firstFailure = !failing;
            failing = true;
            if (firstFailure) {
                log.warn("Redis 心跳失败，丢弃连接池里的旧连接（下一条请求会重建）：{}", error.toString());
            } else {
                log.debug("Redis 心跳仍失败：{}", error.toString());
            }
            if (connectionFactory instanceof LettuceConnectionFactory lettuce) {
                try {
                    lettuce.resetConnection();
                } catch (Exception resetError) {
                    log.warn("重置 Redis 连接池失败：{}", resetError.toString());
                }
            }
        }
    }
}
