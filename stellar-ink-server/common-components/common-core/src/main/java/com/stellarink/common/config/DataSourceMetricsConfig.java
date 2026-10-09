package com.stellarink.common.config;

import com.alibaba.druid.pool.DruidDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Druid 连接池指标：{@code stellar.db.pool.connections{state=active|idle|waiting|max}}。
 *
 * <p><b>为什么要有它</b>：这个项目在跨公网连 MySQL 时踩过「空闲后第一个请求卡 15 秒」
 * （根因是 NAT 悄悄丢掉空闲连接，半开连接会一直占着池子直到 JDBC {@code socketTimeout} 才失败，
 * 见 {@link DruidKeepAliveConfig} 的长注释）。那类问题的**先兆**就是指标里的
 * {@code active} 异常偏高、{@code idle} 贴着 0、或者 {@code waiting} 开始大于 0
 * —— 有曲线之后，「池子被半开连接占满」和「SQL 变慢了」可以一眼分开，
 * 而不必等到用户报「卡 15 秒」再回来啃代码。
 *
 * <p><b>为什么不直接开 Druid 自带的 StatFilter/监控页</b>：那需要引
 * {@code druid-spring-boot-3-starter} 的 Web 监控 Servlet 并暴露一个页面（额外的攻击面与鉴权问题），
 * 而我们要的只是四个数字。四个 Gauge 就够了，不需要多一个端点。
 *
 * <p><b>取不到数据源的场合（{@code @WebMvcTest} 切片、没有 JDBC 的模块）返回 null</b>，
 * 等于不装这组指标 —— 与 {@link DruidKeepAliveConfig#dataSourceKeepAliveHeartbeat} 同一套做法，
 * 避免切片测试因为「没有数据源」启动失败。用 {@code instanceof} 而不是强制转型：
 * 测试档用 H2 时数据源类型可能不是 Druid，那时静默跳过即可，**不能**因此让上下文起不来。
 */
@Slf4j
@Configuration
@ConditionalOnClass({DruidDataSource.class, MeterBinder.class})
public class DataSourceMetricsConfig {

    /** 指标名：{@code stellar_db_pool_connections{state="active"}}（Prometheus 侧）。 */
    public static final String METRIC_POOL = "stellar.db.pool.connections";

    public static final String TAG_STATE = "state";

    @Bean
    MeterBinder stellarInkDataSourceMetrics(ObjectProvider<DataSource> dataSourceProvider) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (!(dataSource instanceof DruidDataSource druid)) {
            log.debug("未发现 Druid 数据源，跳过连接池指标（切片测试或非 JDBC 模块属正常）");
            return null;
        }
        log.info("Druid 连接池指标已生效：maxActive={} initialSize={} minIdle={}"
                        + "（active 偏高 + idle 贴 0 是半开连接占池的先兆，见 DruidKeepAliveConfig）",
                druid.getMaxActive(), druid.getInitialSize(), druid.getMinIdle());
        return registry -> {
            gauge(registry, druid, "active", DruidDataSource::getActiveCount,
                    "正在被业务占用的连接数");
            gauge(registry, druid, "idle", DruidDataSource::getPoolingCount,
                    "池中空闲可借的连接数；长期贴 0 说明池子不够或被半开连接占住");
            gauge(registry, druid, "waiting", DruidDataSource::getWaitThreadCount,
                    "正在等待连接的线程数；持续大于 0 说明池子不够或存在慢 SQL");
            gauge(registry, druid, "max", DruidDataSource::getMaxActive,
                    "池容量上限（配置值），用来判断 active 是否贴顶");
        };
    }

    private static void gauge(MeterRegistry registry,
                              DruidDataSource druid,
                              String state,
                              java.util.function.ToDoubleFunction<DruidDataSource> value,
                              String description) {
        Gauge.builder(METRIC_POOL, druid, value)
                .tag(TAG_STATE, state)
                .description(description)
                .register(registry);
    }
}
