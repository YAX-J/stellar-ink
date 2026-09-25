package com.stellarink.common.redis;

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
 * Redis 连接**保活心跳**：远端 Redis 唯一的真解。
 *
 * <p><b>为什么必须有它</b>（先按顺序排除过别的可能，证据见 AGENTS.md §5）：
 * <ol>
 *   <li>Redis 服务端不掐空闲连接（实测 {@code CONFIG GET timeout} = 0）；</li>
 *   <li>但中间的 NAT / 防火墙会：本机与远端 Redis 之间那条空闲十几分钟的连接会被静默丢弃 ——
 *       {@code netstat} 这边还写着 ESTABLISHED，Redis 那边早就没有这条连接了（实测 11 条对 6 条）；</li>
 *   <li>于是**下一个命令写进了一个黑洞**：等到命令超时（dev 500ms）才发现，网关 fail-closed 回 503、
 *       业务服务回 500。这就是「一段时间不操作就报 503」的全部成因；</li>
 *   <li>而且它不会自愈得快：连接池拿到的这条半开连接 {@code isOpen()} 仍是 true，
 *       Lettuce 的池化工厂 {@code validateObject} 只做 {@code isOpen()}（javap 确认过字节码），
 *       <b>不会发任何网络包</b>，所以 {@code testWhileIdle} / {@code testOnBorrow} 在这里都是空转；
 *       池子里所有连接一起死掉时，重试也只是换一条死连接（实测「两次都失败」）。</li>
 * </ol>
 *
 * <p><b>做法</b>：每 {@code stellar.ink.redis.keepalive.interval}（默认 30s）借一条连接发一次真 PING。
 * 这一下同时办成三件事：① 链路上始终有包，NAT 不会把映射判成空闲；② 借到的那条连接被真实验证；
 * ③ 一旦失败就 {@link LettuceConnectionFactory#resetConnection()} 丢掉整池旧连接，
 * 下一条业务请求会重建 —— 于是失败被关在心跳这一侧，用户看不到 503。
 *
 * <p>配合 {@code max-idle: 1}（连接池只留一条空闲连接）：LIFO 借用保证业务请求拿到的
 * 就是心跳刚热过的那一条；并发多出来的连接用完即销毁，没机会闲到被丢。
 *
 * <p>用 {@link SmartLifecycle} 而不是 {@code @Scheduled}：不需要 {@code @EnableScheduling}，
 * 且 {@code phase = Integer.MAX_VALUE} 让它**最后启动、最先停止**（别在启动途中打 Redis，
 * 也别在连接工厂关闭之后还在发命令）。线程是守护线程，不阻止 JVM 退出。
 */
@Slf4j
public class RedisKeepAliveHeartbeat implements SmartLifecycle {

    /** 心跳间隔（必须明显小于链路的空闲丢弃时间，实测那条约 10 分钟） */
    private final Duration interval;
    private final RedisConnectionFactory connectionFactory;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "redis-keepalive");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running;
    /** 上一次心跳是否失败：只用来避免「Redis 真挂了」时每 30s 刷一条 WARN */
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
        log.info("Redis 保活心跳已启动：每 {} ms 一次（远端 Redis 的空闲连接会被 NAT 静默丢弃）", millis);
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

    /** 最后启动、最先停止 */
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
                log.warn("Redis 心跳失败，丢弃连接池里的旧连接（下一条业务请求会重建）：{}", error.toString());
            } else {
                log.debug("Redis 心跳仍失败：{}", error.toString());
            }
            resetConnection();
        }
    }

    /** 把池子整个丢掉：半开连接只有重建才能摆脱（{@code isOpen()} 认不出它） */
    private void resetConnection() {
        if (connectionFactory instanceof LettuceConnectionFactory lettuce) {
            try {
                lettuce.resetConnection();
            } catch (Exception error) {
                log.warn("重置 Redis 连接池失败：{}", error.toString());
            }
        }
    }
}
