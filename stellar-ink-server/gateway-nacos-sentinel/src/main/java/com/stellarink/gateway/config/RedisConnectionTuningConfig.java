package com.stellarink.gateway.config;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

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
 * 两份的 BeanPostProcessor 完全一致，common-core 那份还多了「让池验活空闲连接」
 * （跨公网的空闲连接会被 NAT 静默掐断，idle 后第一个命令必然等到超时）。
 * <b>改动要同步两处</b>（网关这份也建议补上 testWhileIdle：Reactive Lettuce 同样是池化的）。
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
     * 让连接池**校验空闲连接**：跨公网的连接会被 NAT / 防火墙静默掐断，而它在池里是「空闲」的、
     * 借出来才发命令 —— 于是 **idle 之后的第一个命令必然等到 timeout**（dev 是 500ms）。
     * 对网关来说这正好是「莫名其妙的一次 503，下一个请求又好了」。
     *
     * <p>Spring Boot 在 {@code PoolBuilderFactory} 里只设 maxIdle/minIdle/maxWait/
     * timeBetweenEvictionRuns 四项（javap 确认），**不暴露** {@code testWhileIdle}，
     * 而 commons-pool2 的默认值是 false（同样 javap 确认），淘汰器只按时间清理、不验活。
     * 所以这里按 {@link RedisProperties.Pool} 重建一份等价配置，只把 testWhileIdle 打开；
     * 刻意不用 {@code testOnBorrow}（那会给每个 Redis 操作加一次跨公网 PING）。
     *
     * <p>与 {@code common-core} 的 {@code RedisLettuceTuningConfig} 是同一套逻辑的另一份实现：
     * 网关是 WebFlux，不能依赖带 servlet 的 common-core。<b>改动要同步两处</b>。
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer validateIdleConnections(
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
                // maxWait 默认 -1ms、timeBetweenEvictionRuns 默认 **null**：照搬 setter 会在
                // 没配 pool 的 profile（例如只写了 host/port 的 prod yml）上 NPE，只覆盖配了的项
                if (pool.getMaxWait() != null) {
                    config.setMaxWait(pool.getMaxWait());
                }
                if (pool.getTimeBetweenEvictionRuns() != null) {
                    config.setTimeBetweenEvictionRuns(pool.getTimeBetweenEvictionRuns());
                }
            }
            config.setTestWhileIdle(true);
            pooling.poolConfig(config);
        };
    }
}
