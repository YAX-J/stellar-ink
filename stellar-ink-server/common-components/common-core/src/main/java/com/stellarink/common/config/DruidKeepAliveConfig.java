package com.stellarink.common.config;

import com.alibaba.druid.pool.DruidDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.temporal.ChronoUnit;

/**
 * 跨公网连 MySQL 时的**空闲保活兜底**：把 Druid 的参数在代码里定死一遍。
 *
 * <p><b>为什么必须有它</b>（本地连远端 MySQL 实测，症状是「空闲一段时间后第一个 DB 请求卡 15 秒」）：
 * <ol>
 *   <li>先排除 MySQL 自己掐连接：{@code SHOW VARIABLES LIKE 'wait_timeout'} = <b>28800</b>（8 小时）；</li>
 *   <li>但公网链路的 NAT/防火墙会丢掉空闲连接 —— MySQL 侧 {@code processlist} 里
 *       我们 34 条连接有 24 条 {@code Time} 在 1000~7000 秒，**从没被 ping 过**；</li>
 *   <li>于是空闲后的第一个请求借到一条半开连接：命令写进黑洞，等到 JDBC
 *       {@code socketTimeout}（15s）才失败，Druid 丢掉它换一条新的 —— 用户看到的是
 *       「卡 15 秒然后成功」（正好等于浏览器自己的超时）。</li>
 * </ol>
 *
 * <p><b>为什么在代码里定，而不是只写在 yml</b>：这几个键在**远端 Nacos** 上也有一份
 * （{@code user-service-dev.yaml} 里有 {@code keep-alive-between-time-millis: 120000} 等），
 * 而 Nacos 的键优先于本地 yml —— 只改本地等于白改（这个坑在 Redis 那边已经踩过一次）。
 * 代码里定死并**打印生效值**，才能既不被覆盖、又能在日志里看到实情。
 *
 * <p>取值偏激进是有意的：保活间隔必须**明显小于**链路丢弃空闲连接的时间。
 * 原来的 {@code keep-alive-between-time-millis: 120000} 实测仍然会被丢，
 * 说明中间链路比 2 分钟更早放弃这条流；这里压到 30s 以内，代价只是每 10s 一轮很小的心跳。
 */
@Slf4j
@Component
@ConditionalOnClass(DruidDataSource.class)
@RequiredArgsConstructor
public class DruidKeepAliveConfig implements SmartInitializingSingleton {

    /** 空闲多久纳入保活范围（Druid 默认 30 分钟，远晚于 NAT 丢弃空闲连接的时间） */
    public static final long MIN_EVICTABLE_IDLE_MILLIS = 60_000L;

    /**
     * 保活探测间隔：到点就发一条真 SELECT 1（默认 2 分钟，实测仍被丢）。
     *
     * <p>⚠️ **必须严格大于 {@link #DESTROY_RUN_MILLIS}**，否则 Druid 在 {@code init()} 直接抛
     * {@code SQLException: keepAliveBetweenTimeMillis must be greater than timeBetweenEvictionRunsMillis}
     * 并且**拒绝建连**（整个服务起不来）。踩过一次：两个都写 10s。
     */
    public static final long KEEP_ALIVE_BETWEEN_MILLIS = 30_000L;

    /** 淘汰/保活线程的检查周期（默认 60s，压小一点才能让每条连接 30s 左右轮到一次） */
    public static final long DESTROY_RUN_MILLIS = 10_000L;

    private final ApplicationContext applicationContext;

    @Override
    public void afterSingletonsInstantiated() {
        // Druid 的硬约束：不满足时它在 init() 里直接抛异常、拒绝建连（服务起不来）
        if (KEEP_ALIVE_BETWEEN_MILLIS <= DESTROY_RUN_MILLIS) {
            throw new IllegalStateException("保活间隔必须大于淘汰线程周期，否则 Druid 拒绝建连："
                    + KEEP_ALIVE_BETWEEN_MILLIS + " <= " + DESTROY_RUN_MILLIS);
        }
        applicationContext.getBeansOfType(DruidDataSource.class).forEach((name, dataSource) -> {
            dataSource.setKeepAlive(true);
            dataSource.setMinEvictableIdleTimeMillis(MIN_EVICTABLE_IDLE_MILLIS);
            dataSource.setKeepAliveBetweenTimeMillis(KEEP_ALIVE_BETWEEN_MILLIS);
            dataSource.setTimeBetweenEvictionRunsMillis(DESTROY_RUN_MILLIS);
            log.info("MySQL 空闲保活已生效[{}]：keepAlive={} 保活间隔={}ms 淘汰线程={}ms 空闲门限={}ms"
                            + "（跨公网的空闲连接会被 NAT 静默丢弃，不保活就会「空闲后第一个请求卡 15s」）",
                    name, dataSource.isKeepAlive(), dataSource.getKeepAliveBetweenTimeMillis(),
                    dataSource.getTimeBetweenEvictionRunsMillis(), dataSource.getMinEvictableIdleTimeMillis());
        });
    }

    /**
     * JDBC 保活心跳。**Druid 自带的 keep-alive 不足以覆盖整池**（实测：MySQL 侧只有一部分连接
     * 每十秒被 ping 一次，其余闲置上千秒），所以由我们自己对**每一条空闲连接**发真查询，
     * 见 {@link DataSourceKeepAliveHeartbeat} 的类注释。
     *
     * <p>取不到 DataSource 的场合（`@WebMvcTest` 切片）返回 null —— 那样等于不装心跳，
     * 避免让切片测试因为「没有数据源」启动失败。
     */
    @Bean
    @ConditionalOnProperty(name = "stellar.ink.db.keepalive.enabled", havingValue = "true", matchIfMissing = true)
    DataSourceKeepAliveHeartbeat dataSourceKeepAliveHeartbeat(
            ObjectProvider<DataSource> dataSourceProvider,
            @Value("${stellar.ink.db.keepalive.interval:30s}") String keepAliveInterval) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return null;
        }
        // 与 Redis 那份同理：用 Boot 的 DurationStyle 自己解析，别让 @Value 直接转 Duration
        return new DataSourceKeepAliveHeartbeat(
                dataSource, DurationStyle.detectAndParse(keepAliveInterval, ChronoUnit.MILLIS));
    }
}
