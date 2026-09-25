package com.stellarink.common.config;

import com.alibaba.druid.pool.DruidDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 空闲保活的回归测试。
 *
 * <p>守的是两类故障，都不会在编译期或代码评审时露头：
 * <ol>
 *   <li><b>参数组合被 Druid 拒绝</b>：{@code keepAliveBetweenTimeMillis} 必须**严格大于**
 *       {@code timeBetweenEvictionRunsMillis}，否则 {@code init()} 直接抛
 *       {@code SQLException: keepAliveBetweenTimeMillis must be greater than ...} 并拒绝建连 ——
 *       表现是「三个用库的服务全部起不来」。踩过一次（两个都写 10s），
 *       所以这里既正向跑一遍 {@code init()}，也留一条反向对照把 Druid 的报错钉住；</li>
 *   <li><b>保活门限太晚</b>：Druid 默认「空闲 30 分钟才保活、探测间隔 2 分钟」，
 *       都晚于公网链路丢掉空闲连接的时间（实测 2 分钟就已经被丢）——
 *       业务借到冷连接就会卡满 JDBC {@code socketTimeout}（15s）。</li>
 * </ol>
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
        assertEquals(30_000L, dataSource.getKeepAliveBetweenTimeMillis());
        assertEquals(60_000L, dataSource.getMinEvictableIdleTimeMillis());
        assertEquals(10_000L, dataSource.getTimeBetweenEvictionRunsMillis());
        assertTrue(dataSource.getKeepAliveBetweenTimeMillis() < 120_000L,
                "必须比「实测仍会被丢」的 2 分钟更短");
        dataSource.close();
    }

    @Test
    @DisplayName("Druid 不再因参数约束拒绝这组值（init() 里其他报错与本约束无关）")
    void druidItselfAcceptsTheCombination() {
        DruidDataSource dataSource = new DruidDataSource();
        // 不给真实库、不预建连接：只关心 init() 开头那几条参数校验
        dataSource.setUrl("jdbc:mysql://127.0.0.1:1/not_used");
        dataSource.setInitialSize(0);
        dataSource.setMinIdle(0);
        dataSource.setMaxActive(1);
        new DruidKeepAliveConfig(applicationContextWith(dataSource)).afterSingletonsInstantiated();

        String failure = null;
        try {
            dataSource.init();
        } catch (Exception error) {
            // common-core 的测试类路径上没有 MySQL 驱动，init() 走到建连那步会报别的错 ——
            // 这里只排除「被保活参数约束拒绝」这一种，那才是会让服务整体起不来的错
            failure = error.getMessage();
        } finally {
            dataSource.close();
        }
        assertTrue(failure == null || !failure.contains("keepAliveBetweenTimeMillis"),
                "Druid 因为保活参数拒绝建连：" + failure);
    }

    @Test
    @DisplayName("反向对照：保活间隔不大于淘汰周期时 Druid 直接拒绝建连（线上崩过一次的原因）")
    void druidRejectsEqualIntervals() {
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl("jdbc:mysql://127.0.0.1:1/not_used");
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setInitialSize(0);
        dataSource.setKeepAlive(true);
        dataSource.setKeepAliveBetweenTimeMillis(10_000L);
        dataSource.setTimeBetweenEvictionRunsMillis(10_000L);

        SQLException error = assertThrows(SQLException.class, dataSource::init);
        assertTrue(error.getMessage().contains("keepAliveBetweenTimeMillis"),
                "Druid 的报错文案（诊断时就是靠它定位的）：" + error.getMessage());
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
