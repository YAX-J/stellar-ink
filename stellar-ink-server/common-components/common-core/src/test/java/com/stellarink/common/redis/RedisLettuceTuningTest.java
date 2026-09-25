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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 两条 Redis 加固的回归测试。
 *
 * <p>它们都**不会自己报错**：共享连接被带歪、空闲连接被 NAT 掐断，
 * 表现都是「偶尔一次超时」，重试或重启就好了 —— 所以只能用断言钉住配置，
 * 不能靠「跑一段时间看看」。
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
    @DisplayName("连接池按 yml 的值重建，并打开空闲校验（借出前不校验，避免每个命令一次 PING）")
    void validatesIdleConnections() {
        // RedisProperties 的 lettuce/pool 是 final 字段（只有 getter），就地改它
        RedisProperties properties = new RedisProperties();
        RedisProperties.Pool pool = properties.getLettuce().getPool();
        pool.setMaxIdle(8);
        pool.setMinIdle(2);
        pool.setMaxWait(Duration.ofMillis(500));
        pool.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder();
        new RedisLettuceTuningConfig().validateIdleConnections(providerOf(properties)).customize(builder);
        LettucePoolingClientConfiguration built = (LettucePoolingClientConfiguration) builder.build();

        assertTrue(built.getPoolConfig().getTestWhileIdle(), "空闲连接必须被验活");
        assertFalse(built.getPoolConfig().getTestOnBorrow(), "借出前校验会给每个命令加一次跨公网 PING");
        assertEquals(8, built.getPoolConfig().getMaxIdle());
        assertEquals(2, built.getPoolConfig().getMinIdle());
        assertEquals(Duration.ofMillis(500), built.getPoolConfig().getMaxWaitDuration());
        assertEquals(Duration.ofSeconds(30), built.getPoolConfig().getDurationBetweenEvictionRuns());
    }

    @Test
    @DisplayName("没有 RedisProperties 时（@WebMvcTest 切片里没有 Redis 自动配置）也不炸")
    void toleratesMissingProperties() {
        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder();
        new RedisLettuceTuningConfig().validateIdleConnections(providerOf(null)).customize(builder);

        assertTrue(((LettucePoolingClientConfiguration) builder.build()).getPoolConfig().getTestWhileIdle());
    }

    @Test
    @DisplayName("只配了 host/port（没有 pool 段）也不炸：那两个属性在 Boot 里没有默认值")
    void toleratesUnconfiguredPool() {
        RedisProperties properties = new RedisProperties();
        // timeBetweenEvictionRuns 默认是 null、maxWait 默认 -1ms：照搬 setter 会 NPE
        assertNull(properties.getLettuce().getPool().getTimeBetweenEvictionRuns());

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder();
        new RedisLettuceTuningConfig().validateIdleConnections(providerOf(properties)).customize(builder);

        assertTrue(((LettucePoolingClientConfiguration) builder.build()).getPoolConfig().getTestWhileIdle());
    }

    @Test
    @DisplayName("真的接进 Spring：自动配置造出的工厂就是「关共享连接 + 池验活空闲」")
    void wiringIsAppliedToTheRealConnectionFactory() {
        redisContext().withUserConfiguration(RedisLettuceTuningConfig.class)
                .run(context -> {
                    // 这条最容易假绿：BeanPostProcessor 若晚于 afterPropertiesSet 就完全不起作用
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertFalse(factory.getShareNativeConnection(), "工厂级开关必须真的被改到");
                    LettucePoolingClientConfiguration client = assertInstanceOf(
                            LettucePoolingClientConfiguration.class, factory.getClientConfiguration(),
                            "配了 pool 就该走池");
                    assertTrue(client.getPoolConfig().getTestWhileIdle(), "池必须验活空闲连接");
                    assertEquals(8, client.getPoolConfig().getMaxIdle());
                });
    }

    @Test
    @DisplayName("反向对照：没有本类时，Spring Boot 自己**不会**关掉共享连接、也不会验活空闲连接")
    void springBootAloneDoesNotHardenAnything() {
        redisContext().run(context -> {
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            assertTrue(factory.getShareNativeConnection(),
                    "这就是「配了 pool 也没用」的原因：这个开关只能由代码改");
            LettucePoolingClientConfiguration client = assertInstanceOf(
                    LettucePoolingClientConfiguration.class, factory.getClientConfiguration());
            assertFalse(client.getPoolConfig().getTestWhileIdle(),
                    "Spring Boot 不暴露 test-while-idle，commons-pool2 的默认值就是 false");
        });
    }

    /** 只装 Redis 自动配置的最小上下文（不连真 Redis：连接是懒建的） */
    private static ApplicationContextRunner redisContext() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.lettuce.pool.enabled=true",
                        "spring.data.redis.lettuce.pool.max-idle=8",
                        "spring.data.redis.lettuce.pool.min-idle=2",
                        "spring.data.redis.lettuce.pool.time-between-eviction-runs=30s");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RedisProperties> providerOf(RedisProperties properties) {
        ObjectProvider<RedisProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(properties);
        return provider;
    }
}
