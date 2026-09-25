package com.stellarink.common.config;

import com.alibaba.druid.pool.DruidDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 空闲保活的回归测试。
 *
 * <p>守的是「一片配置看起来没问题、但空闲后第一个 DB 请求卡 15 秒」那种故障：
 * Druid 的 {@code keep-alive} 默认门限是空闲 30 分钟、探测间隔 2 分钟，
 * 都晚于公网链路丢掉空闲连接的时间（实测 2 分钟就已经被丢）。
 * 这里不连数据库，只钉住「代码定死的值」和「真的写进了数据源」。
 */
class DruidKeepAliveConfigTest {

    @Test
    @DisplayName("保活参数真的写进数据源，且间隔明显小于 2 分钟（实测 2 分钟仍会被丢）")
    void appliesKeepAliveToDataSource() {
        DruidDataSource dataSource = new DruidDataSource();
        // 先摆成 Druid 的默认/典型配置，确认是被覆盖而不是本来就这样
        dataSource.setKeepAlive(false);
        dataSource.setKeepAliveBetweenTimeMillis(120_000L);
        dataSource.setMinEvictableIdleTimeMillis(300_000L);
        dataSource.setTimeBetweenEvictionRunsMillis(60_000L);

        new DruidKeepAliveConfig(applicationContextWith(dataSource)).afterSingletonsInstantiated();

        assertTrue(dataSource.isKeepAlive(), "保活必须打开");
        assertEquals(10_000L, dataSource.getKeepAliveBetweenTimeMillis());
        assertEquals(30_000L, dataSource.getMinEvictableIdleTimeMillis());
        assertEquals(10_000L, dataSource.getTimeBetweenEvictionRunsMillis());
        assertTrue(dataSource.getKeepAliveBetweenTimeMillis() < 120_000L,
                "必须比「实测仍会被丢」的 2 分钟更短");
        dataSource.close();
    }

    @Test
    @DisplayName("没有 DruidDataSource 时什么都不做（网关等无数据源的场合）")
    void toleratesNoDataSource() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();
        new DruidKeepAliveConfig(context).afterSingletonsInstantiated();
        assertFalse(context.containsBean("druidDataSource"));
    }

    private static StaticApplicationContext applicationContextWith(DruidDataSource dataSource) {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource", dataSource);
        context.refresh();
        return context;
    }
}
