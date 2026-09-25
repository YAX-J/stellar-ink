package com.stellarink.common.config;

import com.alibaba.druid.pool.DruidDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * JDBC 连接池的**保活心跳**：把池里每一条空闲连接都隔一会儿真查一次。
 *
 * <p><b>为什么 Druid 自带的 {@code keep-alive} 不够</b>（实测）：
 * 它在 {@code shrink()} 里只把「{@code poolingCount - minIdle} 之外的、以及最近使用的那几条」
 * 纳入保活，池子里其余的连接没人管；而 Druid 的检查顺序是「最老的先看」，
 * 一旦碰上一条「既不够旧到淘汰、又不够旧到保活」的连接就直接 {@code break}。
 * 结果就是 MySQL 侧能看到：一部分连接每十秒被 ping 一次（{@code time} 归零），
 * 另一部分却闲置上千秒 —— 业务请求恰好借到后者时，命令写进被 NAT 丢弃的连接，
 * 要等 JDBC {@code socketTimeout}（15s）才失败换连接，用户看到的就是「刷新后卡 15 秒」。
 *
 * <p><b>做法</b>：每 {@code stellar.ink.db.keepalive.interval}（默认 30s）
 * **同时借出 N 条连接**（N = 池的 initial-size，即稳态下池里几乎全部的连接），
 * 每条跑一次 {@code SELECT 1}，然后全部归还。之所以要「同时借」：
 * Druid 的借用是 LIFO（取最近归还的那条），一条一条借还只会反复命中同一条，
 * 同时借出才会拿到 N 条不同的连接 —— 这才叫「整池保活」。
 *
 * <p>心跳发送失败只记日志（第一次 WARN、之后 DEBUG、恢复时 INFO），
 * 不打断业务：坏连接由 Druid 自己淘汰。
 */
@Slf4j
public class DataSourceKeepAliveHeartbeat implements SmartLifecycle, DisposableBean {

    /** 保活间隔：必须明显小于链路丢弃空闲连接的时间（实测那条链路十几分钟就丢） */
    private final Duration interval;
    private final DataSource dataSource;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "db-keepalive");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running;
    private volatile boolean failing;

    public DataSourceKeepAliveHeartbeat(DataSource dataSource, Duration interval) {
        this.dataSource = dataSource;
        this.interval = interval;
    }

    /** 每次心跳要覆盖几条连接：取池的 initial-size（稳态下池里就这么多） */
    int connectionsPerBeat() {
        if (dataSource instanceof DruidDataSource druid) {
            return Math.max(1, druid.getInitialSize());
        }
        return 1;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        long millis = Math.max(1000L, interval.toMillis());
        scheduler.scheduleWithFixedDelay(this::beat, millis, millis, TimeUnit.MILLISECONDS);
        log.info("MySQL 保活心跳已启动：每 {} ms 同时热 {} 条连接（把被 NAT 丢弃的空闲连接变成不可能）",
                millis, connectionsPerBeat());
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

    @Override
    public void destroy() {
        stop();
    }

    /** 心跳本体。包级可见：测试直接调它，不必等 30 秒 */
    void beat() {
        int count = connectionsPerBeat();
        List<Connection> held = new ArrayList<>(count);
        try {
            // 必须同时借：Druid 的借用是 LIFO，借一条还一条只会反复命中同一条
            for (int index = 0; index < count; index++) {
                held.add(dataSource.getConnection());
            }
            for (Connection connection : held) {
                try (Statement statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                    resultSet.next();
                }
            }
            if (failing) {
                failing = false;
                log.info("MySQL 保活心跳恢复正常");
            }
        } catch (Exception error) {
            boolean firstFailure = !failing;
            failing = true;
            if (firstFailure) {
                log.warn("MySQL 保活心跳失败（坏连接交给 Druid 淘汰，不影响业务）：{}", error.toString());
            } else {
                log.debug("MySQL 保活心跳仍失败：{}", error.toString());
            }
        } finally {
            for (Connection connection : held) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // 归还失败也无所谓：Druid 会按 remove-abandoned 回收
                }
            }
        }
    }
}
