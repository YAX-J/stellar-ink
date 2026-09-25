package com.stellarink.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 共享连接必须被关掉 —— 这是「全站 5~16 秒 503」那条链路的开关。
 *
 * <p>这条断言看着很小，但它守的是一个**只在故障时才看得见**的差异：
 * 开着共享连接时，一条命令超时会让整条流水线的应答错位，之后每个撤销校验都超时；
 * 关掉之后坏连接最多影响一个操作。而它不是配置项（Boot 3.2 没有这个键），
 * 只能由这里的 BeanPostProcessor 设 —— 所以必须有用例盯着，否则哪天有人删了它，
 * 表现会是「偶尔全站 503 又自己好了」，没人能联想到这行代码。
 */
class RedisConnectionTuningConfigTest {

    @Test
    @DisplayName("默认开启共享连接 → 处理后必须变成 false")
    void disablesSharedNativeConnection() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 6379));
        assertTrue(factory.getShareNativeConnection(), "前提：Lettuce 默认就是共享一条连接");

        BeanPostProcessor processor = RedisConnectionTuningConfig.disableSharedNativeConnection();
        Object returned = processor.postProcessBeforeInitialization(factory, "redisConnectionFactory");

        assertFalse(factory.getShareNativeConnection(), "共享连接必须被关掉，否则一条坏连接会拖垮全部请求");
        assertTrue(returned == factory, "后置处理器必须原样返回这个 bean，别把工厂换掉");
    }

    @Test
    @DisplayName("其它 bean 不受影响")
    void leavesOtherBeansAlone() {
        BeanPostProcessor processor = RedisConnectionTuningConfig.disableSharedNativeConnection();
        Object other = new Object();

        assertTrue(processor.postProcessBeforeInitialization(other, "anything") == other);
    }

    @Test
    @DisplayName("真的接进 Spring：自动配置造出的工厂就是「关共享连接 + 池验活空闲」")
    void wiringIsAppliedToTheRealConnectionFactory() {
        redisContext().withUserConfiguration(RedisConnectionTuningConfig.class)
                .run(context -> {
                    // 这条最容易假绿：BeanPostProcessor 若晚于 afterPropertiesSet 就完全不起作用
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertFalse(factory.getShareNativeConnection(), "工厂级开关必须真的被改到");
                    LettucePoolingClientConfiguration client = assertInstanceOf(
                            LettucePoolingClientConfiguration.class, factory.getClientConfiguration(),
                            "配了 pool 就该走池");
                    assertTrue(client.getPoolConfig().getTestWhileIdle(),
                            "池必须验活空闲连接，否则 idle 后第一个请求必然 503");
                });
    }

    @Test
    @DisplayName("反向对照：没有本类时，Spring Boot 自己不会关共享连接、也不会验活空闲连接")
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

    @Test
    @DisplayName("只配了 host/port（prod yml 就没有 pool 段）也不炸：那两个属性在 Boot 里没有默认值")
    void toleratesUnconfiguredPool() {
        RedisProperties properties = new RedisProperties();
        assertNull(properties.getLettuce().getPool().getTimeBetweenEvictionRuns());

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder();
        new RedisConnectionTuningConfig()
                .validateIdleConnections(providerOf(properties))
                .customize(builder);

        assertTrue(((LettucePoolingClientConfiguration) builder.build()).getPoolConfig().getTestWhileIdle());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RedisProperties> providerOf(RedisProperties properties) {
        ObjectProvider<RedisProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(properties);
        return provider;
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
}
