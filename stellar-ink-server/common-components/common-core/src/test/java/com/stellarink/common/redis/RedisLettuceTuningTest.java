package com.stellarink.common.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lettuce 加固的回归测试。
 *
 * <p>两处都**不会自己报错**，只会表现成「偶尔一次 503/500，重试就好」，
 * 所以只能用断言钉住配置，不能靠「跑一段时间看看」：
 * <ol>
 *   <li>共享原生连接必须关掉：开着时一条命令超时会让整条流水线的应答错位；</li>
 *   <li>连接池只留**一条**空闲连接：让保活心跳热的那条正好是业务要借的那条
 *       （{@code RedisKeepAliveHeartbeat} 才是治「空闲后被 NAT 丢包」的正主，
 *       池子尺寸只是配合它）。</li>
 * </ol>
 */
class RedisLettuceTuningTest {

    @Test
    @DisplayName("共享原生连接被关掉（否则一条坏连接会让所有 Redis 操作一起超时）")
    void disablesSharedNativeConnection() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        assertTrue(factory.getShareNativeConnection(), "前提：Lettuce 默认就是共享连接");

        RedisLettuceTuningConfig.disableSharedNativeConnection()
                .postProcessBeforeInitialization(factory, "lettuceConnectionFactory");

        assertFalse(factory.getShareNativeConnection());
    }

    @Test
    @DisplayName("连接池按 yml 重建：一条空闲连接；不靠 testWhileIdle（它只做 isOpen()，认不出半开连接）")
    void appliesPoolConfiguration() {
        // RedisProperties 的 lettuce/pool 是 final 字段（只有 getter），就地改它
        RedisProperties properties = new RedisProperties();
        RedisProperties.Pool pool = properties.getLettuce().getPool();
        pool.setMaxIdle(1);
        pool.setMinIdle(1);
        pool.setMaxWait(Duration.ofMillis(500));
        pool.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder();
        new RedisLettuceTuningConfig().tuneConnectionPool(providerOf(properties)).customize(builder);
        LettucePoolingClientConfiguration built = (LettucePoolingClientConfiguration) builder.build();

        assertEquals(1, built.getPoolConfig().getMaxIdle());
        assertEquals(1, built.getPoolConfig().getMinIdle());
        assertEquals(Duration.ofMillis(500), built.getPoolConfig().getMaxWaitDuration());
        assertEquals(Duration.ofSeconds(30), built.getPoolConfig().getDurationBetweenEvictionRuns());
        // 刻意不开这两个：Lettuce 的池化工厂 validateObject() = StatefulConnection.isOpen()，
        // 那是本地标志位，对「被 NAT 静默丢弃的半开连接」永远返回 true —— 开了只是白跑，
        // 还会让后来的人以为「配了校验就安全了」。真正管用的是每 30s 一次真 PING 的心跳。
        assertFalse(built.getPoolConfig().getTestWhileIdle(), "别给虚假的安全感");
        assertFalse(built.getPoolConfig().getTestOnBorrow(),
                "借出前校验同样只做 isOpen()，却要给每个命令加一次跨公网 PING");
    }

    @Test
    @DisplayName("没有 RedisProperties 时（@WebMvcTest 切片里没有 Redis 自动配置）也不炸")
    void toleratesMissingProperties() {
        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder();
        new RedisLettuceTuningConfig().tuneConnectionPool(providerOf(null)).customize(builder);

        assertNotNull(((LettucePoolingClientConfiguration) builder.build()).getPoolConfig());
    }

    @Test
    @DisplayName("只配了 host/port（没有 pool 段）也不炸：那两个属性在 Boot 里没有默认值")
    void toleratesUnconfiguredPool() {
        RedisProperties properties = new RedisProperties();
        // timeBetweenEvictionRuns 默认是 null、maxWait 默认 -1ms：照搬 setter 会 NPE
        assertNull(properties.getLettuce().getPool().getTimeBetweenEvictionRuns());

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder();
        new RedisLettuceTuningConfig().tuneConnectionPool(providerOf(properties)).customize(builder);

        assertNotNull(((LettucePoolingClientConfiguration) builder.build()).getPoolConfig());
    }

    @Test
    @DisplayName("真的接进 Spring：自动配置造出的工厂就是「关共享连接 + 单条空闲连接」")
    void wiringIsAppliedToTheRealConnectionFactory() {
        redisContext().withUserConfiguration(RedisLettuceTuningConfig.class)
                .run(context -> {
                    // 这条最容易假绿：BeanPostProcessor 若晚于 afterPropertiesSet 就完全不起作用
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertFalse(factory.getShareNativeConnection(), "工厂级开关必须真的被改到");
                    LettucePoolingClientConfiguration client = assertInstanceOf(
                            LettucePoolingClientConfiguration.class, factory.getClientConfiguration(),
                            "配了 pool 就该走池");
                    assertEquals(1, client.getPoolConfig().getMaxIdle(),
                            "池里只留一条空闲连接：心跳热的就是业务要借的那条（LIFO）");
                    assertEquals(1, client.getPoolConfig().getMinIdle());
                });
    }

    @Test
    @DisplayName("反向对照：没有本类时，Spring Boot 自己**不会**关掉共享连接")
    void springBootAloneDoesNotHardenAnything() {
        redisContext().run(context -> {
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            assertTrue(factory.getShareNativeConnection(),
                    "这就是「配了 pool 也没用」的原因：这个开关只能由代码改");
            assertInstanceOf(LettucePoolingClientConfiguration.class, factory.getClientConfiguration(),
                    "池本身是 Boot 开的，我们只调整尺寸");
        });
    }

    @Test
    @DisplayName("心跳间隔按 yml 的写法解析（30s / PT30S / 裸毫秒）")
    void parsesKeepAliveInterval() {
        assertEquals(Duration.ofSeconds(30), RedisLettuceTuningConfig.parseKeepAliveInterval("30s"));
        assertEquals(Duration.ofSeconds(30), RedisLettuceTuningConfig.parseKeepAliveInterval("PT30S"));
        assertEquals(Duration.ofMillis(500), RedisLettuceTuningConfig.parseKeepAliveInterval("500"));
    }

    /** 只装 Redis 自动配置的最小上下文（不连真 Redis：连接是懒建的） */
    private static ApplicationContextRunner redisContext() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.lettuce.pool.enabled=true",
                        "spring.data.redis.lettuce.pool.max-idle=1",
                        "spring.data.redis.lettuce.pool.min-idle=1",
                        "spring.data.redis.lettuce.pool.time-between-eviction-runs=30s");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RedisProperties> providerOf(RedisProperties properties) {
        ObjectProvider<RedisProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(properties);
        return provider;
    }
}
