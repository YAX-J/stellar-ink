package com.stellarink.common.config;

import com.alibaba.druid.pool.DruidDataSource;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * 连接池指标的注册条件。
 *
 * <p>这里测的不是「数字对不对」（那由 Druid 决定），而是**什么时候不该装**：
 * 这个类位于 {@code com.stellarink.common}，会被 ai-service 的 12 个 {@code @WebMvcTest} 切片
 * 一起装配（启动类有显式 {@code @ComponentScan}），而切片里既没有 DataSource、
 * 数据源也可能不是 Druid。装不上是正常的，**让切片上下文起不来才是事故**。
 */
class DataSourceMetricsConfigTest {

    private static final String[] STATES = {"active", "idle", "waiting", "max"};

    @Test
    @DisplayName("有 Druid 数据源时注册 active/idle/waiting/max 四个状态")
    void registersFourStatesForDruid() {
        DruidDataSource druid = new DruidDataSource();
        druid.setMaxActive(8);
        druid.setInitialSize(2);
        druid.setMinIdle(1);
        // 不调 init()、也不调 close()：只设了参数的数据源不持有任何连接，
        // 指标读的正是这些配置值（以及为 0 的运行时计数）。

        MeterBinder binder = new DataSourceMetricsConfig().stellarInkDataSourceMetrics(providerOf(druid));
        assertNotNull(binder, "有 Druid 数据源就该装这组指标");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        binder.bindTo(registry);

        for (String state : STATES) {
            assertNotNull(registry.find(DataSourceMetricsConfig.METRIC_POOL).tag("state", state).gauge(),
                    "缺少状态：" + state);
        }
        assertEquals(8d, registry.get(DataSourceMetricsConfig.METRIC_POOL)
                .tag("state", "max").gauge().value(), "max 应当读的是配置上限");
    }

    @Test
    @DisplayName("取不到数据源（@WebMvcTest 切片）时返回 null：不装指标，也不让上下文起不来")
    void skipsWhenNoDataSource() {
        assertNull(new DataSourceMetricsConfig().stellarInkDataSourceMetrics(providerOf(null)));
    }

    @Test
    @DisplayName("数据源不是 Druid 时静默跳过（测试档用 H2 时就是这种情况）")
    void skipsWhenDataSourceIsNotDruid() {
        assertNull(new DataSourceMetricsConfig()
                .stellarInkDataSourceMetrics(providerOf(mock(DataSource.class))));
    }

    /**
     * 最小的 {@link ObjectProvider} 替身。
     *
     * <p>要把 {@code getObject()} / {@code getObject(Object...)} / {@code getIfAvailable()} /
     * {@code getIfUnique()} 都显式实现：在这个 Spring 版本里它们**全是抽象方法**
     * （不是带默认实现的便利方法），少写一个就编译不过。
     * 四者在替身里语义等价 —— 它手里最多只有一个候选。
     */
    private static ObjectProvider<DataSource> providerOf(DataSource dataSource) {
        return new ObjectProvider<>() {
            @Override
            public DataSource getObject() {
                if (dataSource == null) {
                    throw new NoSuchBeanDefinitionException(DataSource.class);
                }
                return dataSource;
            }

            @Override
            public DataSource getObject(Object... args) {
                return getObject();
            }

            @Override
            public DataSource getIfAvailable() {
                return dataSource;
            }

            @Override
            public DataSource getIfUnique() {
                return dataSource;
            }

            @Override
            public Iterator<DataSource> iterator() {
                return dataSource == null
                        ? Collections.emptyIterator()
                        : List.of(dataSource).iterator();
            }
        };
    }
}
