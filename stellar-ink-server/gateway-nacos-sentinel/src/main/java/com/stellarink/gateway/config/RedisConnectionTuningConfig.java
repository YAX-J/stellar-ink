package com.stellarink.gateway.config;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import java.time.temporal.ChronoUnit;

/**
 * 关掉 Lettuce 的「共享原生连接」：一条连接坏掉不该让整站判成 503。
 *
 * <p><b>为什么必须显式关</b>（实测踩过，今天 40 次 503）：
 * Lettuce 默认把**所有命令**复用同一条原生连接。这条连接上只要有一次命令超时，
 * 后续命令的应答就会与请求错位，于是**每一个**撤销校验都超时 —— 现象是
 * 「全站带 token 的请求连续 5~16 秒 503，然后自己好了」，而 Lettuce 一条日志都不打
 * （连接在它看来是活的）、JVM 的 GC 也可忽略。关掉之后一个操作借一条连接，
 * 坏连接最多影响那一个操作，并被连接池淘汰。
 *
 * <p><b>为什么不用配置项</b>：`shareNativeConnection` 是
 * {@link LettuceConnectionFactory} 的**工厂级**字段，
 * Spring Boot 3.2 的 {@code spring.data.redis.lettuce} 下**没有**这个键
 * （只有 {@code shutdown-timeout} / {@code pool} / {@code cluster}），
 * 启用连接池也**不会**自动关掉共享连接（用 javap 查过字节码：自动配置里没有
 * {@code setShareNativeConnection} 调用）。写进 yml 是无效的，只会骗后来的人。
 *
 * <p>放在 {@code postProcessBeforeInitialization}：必须早于 {@code afterPropertiesSet()}，
 * 那是工厂决定用「共享连接」还是「池」的地方。
 *
 * <p>⚠️ 业务服务（user / content / ai）用的是 <b>common-core 里的同名类
 * {@code com.stellarink.common.redis.RedisLettuceTuningConfig}</b> —— 网关是 WebFlux，
 * 不能依赖带 servlet 的 common-core，所以这里必须独立存在一份。
 * 两份实现完全一致（关共享连接 + 池子配置 + 保活心跳），<b>改动要同步两处</b>。
 */
@Configuration
@ConditionalOnClass({LettuceConnectionFactory.class, RedisProperties.class})
public class RedisConnectionTuningConfig {

    @Bean
    static BeanPostProcessor disableSharedNativeConnection() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof LettuceConnectionFactory factory
                        && factory.getShareNativeConnection()) {
                    factory.setShareNativeConnection(false);
                }
                return bean;
            }
        };
    }

    /**
     * 按 yml 重建连接池配置（含把空闲连接压到一条的 {@code max-idle: 1}）。
     *
     * <p>⚠️ **刻意不开 {@code testWhileIdle} / {@code testOnBorrow}**：Lettuce 的池化工厂
     * {@code RedisPooledObjectFactory.validateObject()} 只做 {@code isOpen()}
     * （javap 确认字节码），那是个**本地标志位** —— 被 NAT 静默丢弃的半开连接照样返回 true，
     * 校验既不发网络包也发现不了。真正管用的是下面的 {@link RedisKeepAliveHeartbeat}。
     *
     * <p>Spring Boot 在 {@code PoolBuilderFactory} 里只设 maxIdle/minIdle/maxWait/
     * timeBetweenEvictionRuns 四项，而 maxWait 默认 -1ms、timeBetweenEvictionRuns 默认 **null**
     * （照搬 setter 会在没配 pool 的 profile 上 NPE），因此只覆盖真的配了的项。
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer tuneConnectionPool(
            ObjectProvider<RedisProperties> propertiesProvider) {
        return builder -> {
            if (!(builder instanceof LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder pooling)) {
                return;
            }
            GenericObjectPoolConfig<Object> config = new GenericObjectPoolConfig<>();
            RedisProperties properties = propertiesProvider.getIfAvailable();
            RedisProperties.Pool pool = properties == null || properties.getLettuce() == null
                    ? null : properties.getLettuce().getPool();
            if (pool != null) {
                config.setMaxIdle(pool.getMaxIdle());
                config.setMinIdle(pool.getMinIdle());
                if (pool.getMaxWait() != null) {
                    config.setMaxWait(pool.getMaxWait());
                }
                if (pool.getTimeBetweenEvictionRuns() != null) {
                    config.setTimeBetweenEvictionRuns(pool.getTimeBetweenEvictionRuns());
                }
            }
            pooling.poolConfig(config);
        };
    }

    /**
     * Redis 保活心跳（网关版，**响应式池**）。生产环境 Redis 与网关同机也照样开：
     * 同机同样有 keepalive 超时（Docker 网桥 / 宿主防火墙），代价只是每 30s 一次 PING。
     *
     * <p>⚠️ 这里注入的是 {@link ReactiveRedisConnectionFactory}：Spring Data Redis 的阻塞路径与
     * 响应式路径是**两个独立的连接池**（`pools` vs `asyncPools`），网关用的是响应式，
     * 心跳必须打在同一个池上，否则等于没打（踩过一次，见 {@link RedisKeepAliveHeartbeat} 的类注释）。
     */
    @Bean
    @ConditionalOnProperty(name = "stellar.ink.redis.keepalive.enabled", havingValue = "true", matchIfMissing = true)
    RedisKeepAliveHeartbeat redisKeepAliveHeartbeat(
            ObjectProvider<ReactiveRedisConnectionFactory> connectionFactoryProvider,
            @Value("${stellar.ink.redis.keepalive.interval:30s}") String keepAliveInterval) {
        ReactiveRedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        if (connectionFactory == null) {
            return null;
        }
        // 用 Boot 的 DurationStyle 自己解析：@Value 直接转 Duration 依赖 Boot 注册的
        // ApplicationConversionService，而 ApplicationContextRunner 那种最小上下文里没有它
        return new RedisKeepAliveHeartbeat(connectionFactory,
                DurationStyle.detectAndParse(keepAliveInterval, ChronoUnit.MILLIS));
    }
}
