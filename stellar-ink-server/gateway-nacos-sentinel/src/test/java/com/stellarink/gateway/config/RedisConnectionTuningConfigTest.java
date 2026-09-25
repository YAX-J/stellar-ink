package com.stellarink.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关侧 Redis 加固的回归测试。
 *
 * <p>共享连接必须关掉 —— 这是「全站 5~16 秒 503」那条链路的开关；而**真正**解决
 * 「一段时间不操作就 503」的是 {@link RedisKeepAliveHeartbeat}：
 * 撤销校验是 fail-closed，一条被 NAT 静默丢弃的空闲连接会直接变成 503，
 * 而 Lettuce 的池化工厂 validateObject() 只做 `isOpen()`（本地标志位），
 * 配 `testWhileIdle` / `testOnBorrow` 都发现不了 —— 必须真发一次 PING。
 *
 * <p>**最要紧的一条**：心跳必须打在**响应式**池上。Spring Data Redis 的阻塞路径
 * （`getConnection`）与响应式路径（`getConnectionAsync`）用的是两个独立的池，
 * 网关的撤销校验走后者；打错池的表现是「心跳日志一切正常，7~10 分钟后照样 503」。
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
    @DisplayName("心跳必须走响应式池（阻塞/响应式是两个池，打错池 = 等于没打）")
    void heartbeatPingsTheReactivePool() {
        ReactiveRedisConnectionFactory healthy = mock(ReactiveRedisConnectionFactory.class);
        ReactiveRedisConnection connection = mock(ReactiveRedisConnection.class);
        when(healthy.getReactiveConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn(Mono.just("PONG"));

        new RedisKeepAliveHeartbeat(healthy, java.time.Duration.ofSeconds(30)).beat();

        // 反证：如果这里换成阻塞的 getConnection()，撤销校验那条连接根本不会被热到
        verify(healthy).getReactiveConnection();
        verify(connection).ping();
        verify(connection).close();
    }

    @Test
    @DisplayName("心跳失败（拿不到连接）→ 丢掉整池（半开连接只有重建才能摆脱）")
    void heartbeatResetsPoolWhenConnectionFails() {
        LettuceConnectionFactory broken = mock(LettuceConnectionFactory.class);
        // 用「拿连接就抛」来构造失败：LettuceConnectionFactory 覆写后的返回类型
        // LettuceReactiveRedisConnection 是包级私有的，测试里 mock 不出来
        when(broken.getReactiveConnection()).thenThrow(new IllegalStateException("Redis command timed out"));

        assertDoesNotThrow(() -> new RedisKeepAliveHeartbeat(broken, java.time.Duration.ofSeconds(30)).beat());
        verify(broken).resetConnection();
    }

    @Test
    @DisplayName("心跳失败不能把线程打死（下个周期照常再试）")
    void heartbeatSwallowsPingFailure() {
        ReactiveRedisConnectionFactory broken = mock(ReactiveRedisConnectionFactory.class);
        ReactiveRedisConnection connection = mock(ReactiveRedisConnection.class);
        when(broken.getReactiveConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn(Mono.error(new IllegalStateException("Redis command timed out")));

        assertDoesNotThrow(() -> new RedisKeepAliveHeartbeat(broken, java.time.Duration.ofSeconds(30)).beat());
    }

    @Test
    @DisplayName("真的接进 Spring：自动配置造出的工厂就是「关共享连接 + 单条空闲连接」")
    void wiringIsAppliedToTheRealConnectionFactory() {
        redisContext().withUserConfiguration(RedisConnectionTuningConfig.class)
                .run(context -> {
                    // 这条最容易假绿：BeanPostProcessor 若晚于 afterPropertiesSet 就完全不起作用
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertFalse(factory.getShareNativeConnection(), "工厂级开关必须真的被改到");
                    LettucePoolingClientConfiguration client = assertInstanceOf(
                            LettucePoolingClientConfiguration.class, factory.getClientConfiguration(),
                            "配了 pool 就该走池");
                    assertTrue(client.getPoolConfig().getMaxIdle() == 1,
                            "池里只留一条空闲连接：心跳热的就是业务要借的那条");
                    assertFalse(client.getPoolConfig().getTestWhileIdle(),
                            "别开这个：它只做 isOpen()，对半开连接无效，会给人虚假的安全感");
                    // 心跳 bean 也要真的被装上（撤销校验 fail-closed，没有它就会周期性 503）
                    assertTrue(context.containsBean("redisKeepAliveHeartbeat"));
                });
    }

    @Test
    @DisplayName("反向对照：没有本类时，Spring Boot 自己不会关共享连接")
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
    @DisplayName("只配了 host/port（prod yml 就没有 pool 段）也不炸：那两个属性在 Boot 里没有默认值")
    void toleratesUnconfiguredPool() {
        RedisProperties properties = new RedisProperties();
        assertNull(properties.getLettuce().getPool().getTimeBetweenEvictionRuns());

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder();
        new RedisConnectionTuningConfig().tuneConnectionPool(providerOf(properties)).customize(builder);

        assertInstanceOf(LettucePoolingClientConfiguration.class, builder.build());
    }

    @Test
    @DisplayName("关掉心跳后不该有那个 bean（ai-service 就按边界这么关的）")
    void heartbeatCanBeDisabled() {
        redisContext()
                .withUserConfiguration(RedisConnectionTuningConfig.class)
                .withPropertyValues("stellar.ink.redis.keepalive.enabled=false")
                .run(context -> assertFalse(context.containsBean("redisKeepAliveHeartbeat")));
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
                        "spring.data.redis.lettuce.pool.max-idle=1",
                        "spring.data.redis.lettuce.pool.min-idle=1",
                        "spring.data.redis.lettuce.pool.time-between-eviction-runs=30s");
    }
}
