package com.stellarink.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redis 连接保活心跳（网关版，**走响应式 API**）。
 *
 * <p>与 {@code com.stellarink.common.redis.RedisKeepAliveHeartbeat} 是同一套逻辑的另一份实现：
 * 网关是 WebFlux，不能依赖带 servlet 的 common-core。<b>改动要同步两处</b>。
 *
 * <p><b>为什么这一份必须用响应式 API</b>（第一版写错了，被实测打回）：
 * Spring Data Redis 的阻塞路径与响应式路径走的是**两个不同的连接池** ——
 * 反编译 {@code LettucePoolingConnectionProvider} 可以看清：
 * 阻塞的 {@code getConnection(Class)} 用 {@code pools}（commons-pool2 的
 * {@code GenericObjectPool}），响应式的 {@code getConnectionAsync(Class)} 用
 * {@code asyncPools}（Lettuce 自己的 {@code BoundedAsyncPool}）。
 * 网关的撤销校验用的是 {@code ReactiveStringRedisTemplate}，即第二个池。第一版心跳用阻塞 API，
 * 结果只把第一个池里那条「谁也不用」的连接 ping 热了，真正被用的响应式池依旧闲到被 NAT 丢弃 ——
 * 实测：19:02 重启、心跳日志正常、19:09 照样 503，而 Redis 侧那条我们从没发过命令的连接
 * {@code cmd=client|setinfo}、{@code idle == age} 就是证据。
 *
 * <p>为什么网关尤其需要它：撤销校验是 **fail-closed**，一条被 NAT 静默丢弃的空闲连接
 * 会直接变成「全站带 token 的请求 503」——用户看到的就是「一段时间不操作就报 503」。
 *
 * <p>心跳每 {@code stellar.ink.redis.keepalive.interval}（默认 30s）借一条连接发一次真 PING：
 * 链路永远不空闲 → NAT 不会丢映射；借到的那条被真实验证；一旦失败就丢掉整池、
 * 让下一条请求重建。心跳线程是守护线程，且最后启动、最先停止。
 */
@Slf4j
public class RedisKeepAliveHeartbeat implements SmartLifecycle {

    /** 单次心跳最多等多久（比命令超时宽裕，但绝不会把线程挂死） */
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(5);

    private final Duration interval;
    private final ReactiveRedisConnectionFactory connectionFactory;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "redis-keepalive");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running;
    private volatile boolean failing;

    public RedisKeepAliveHeartbeat(ReactiveRedisConnectionFactory connectionFactory, Duration interval) {
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
        log.info("Redis 保活心跳已启动（响应式池）：每 {} ms 一次（撤销校验 fail-closed，连接不能闲死）", millis);
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
        try (ReactiveRedisConnection connection = connectionFactory.getReactiveConnection()) {
            connection.ping().block(PING_TIMEOUT);
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
