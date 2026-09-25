package com.stellarink.common.redis;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

/**
 * Lettuce 的两处加固：**别让一条坏连接毁掉整个服务**。
 *
 * <p>背景（用户反复反馈「莫名其妙 503 / 登录失败」，逐条查出来的）：
 * Redis 在**远端**（与 MySQL / Nacos 同机），本机跨公网连它（实测往返 30~50ms）。
 * 这条链路上有两类失败，都不是「Redis 挂了」：
 * <ol>
 *   <li><b>共享原生连接被一条超时命令带歪</b>：Lettuce 默认把所有命令复用同一条原生连接，
 *       这条连接上只要有一次命令超时，后续应答就会与请求错位，于是**每一个** Redis 操作都超时 ——
 *       网关表现为「全站带 token 的请求连续几秒 503，然后自己好了」，业务服务表现为
 *       500「系统繁忙」。第 ① 条对策见 {@link #disableSharedNativeConnection()}。</li>
 *   <li><b>空闲连接被 NAT / 防火墙静默掐断</b>：连接在池里躺着是「空闲」的，借出来才发命令，
 *       于是**每个 idle 之后的第一个命令**都要等到 {@code timeout} 才报
 *       {@code RedisCommandTimeoutException}（实测：服务重启 12 分钟后第一次登录就撞上）。
 *       第 ② 条对策见 {@link #validateIdleConnections(RedisProperties)}。</li>
 * </ol>
 *
 * <p>放在 common-core 是为了让 user / content 两个业务服务一起受益（它们与网关是两套独立装配：
 * 网关是 WebFlux，不能依赖带 servlet 的 common-core，所以它自带一份
 * {@code com.stellarink.gateway.config.RedisConnectionTuningConfig}，改动要同步）。
 * ai-service 排除了 Redis 自动配置，这里自然不生效。
 */
@Configuration
@ConditionalOnClass({LettuceConnectionFactory.class, RedisProperties.class})
public class RedisLettuceTuningConfig {

    /**
     * 关掉 Lettuce 的「共享原生连接」。
     *
     * <p><b>为什么必须显式关</b>：这是 {@link LettuceConnectionFactory} 的**工厂级**字段，
     * Spring Boot 3.2 的 {@code spring.data.redis.lettuce} 下**没有**这个键
     * （只有 {@code shutdown-timeout} / {@code pool} / {@code cluster}），
     * 启用连接池也**不会**自动关掉共享连接（用 javap 查过自动配置字节码：里面没有
     * {@code setShareNativeConnection} 调用）。写进 yml 是无效的，只会骗后来的人。
     *
     * <p>放在 {@code postProcessBeforeInitialization}：必须早于 {@code afterPropertiesSet()}，
     * 那是工厂决定用「共享连接」还是「池」的地方。
     */
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
     * 让连接池**校验空闲连接**（借出前不校验，省掉每个命令一次 PING 的往返）。
     *
     * <p>为什么自己造一份 {@link GenericObjectPoolConfig}：Spring Boot 在
     * {@code LettuceConnectionConfiguration$PoolBuilderFactory} 里只设
     * {@code maxIdle / minIdle / maxWait / timeBetweenEvictionRuns} 四项（javap 确认），
     * 且**不暴露** {@code testWhileIdle}；而 commons-pool2 的默认值是 {@code false}
     * （同样 javap 确认），也就是淘汰器只是「按时间清理」，不检查连接是不是还活着。
     * 对远端 Redis 来说这正是漏网的那一类失败，所以这里按 {@link RedisProperties.Pool}
     * 重建一份完全等价的配置，只把 {@code testWhileIdle} 打开。
     *
     * <p>没有用 {@code testOnBorrow}：那会给**每个** Redis 操作加一次 PING 往返
     * （跨公网 30~50ms），代价太大；空闲校验是后台线程每
     * {@code time-between-eviction-runs}（约定 30s）做一次，够用且几乎免费。
     *
     * <p>{@link RedisProperties} 用 {@link ObjectProvider} 取而不是构造器注入：
     * 各服务有 `@WebMvcTest` 切片（启动类的显式 {@code @ComponentScan} 会把本类一起装进去），
     * 而切片里没有 Redis 自动配置，硬依赖会让切片启动失败。
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
                // 这两个在 RedisProperties.Pool 里**没有默认值**（maxWait 默认 -1ms，
                // timeBetweenEvictionRuns 默认 null），照搬 setter 会在没配 pool 的服务上 NPE
                // （ai-service 就没配）—— 只覆盖真的配了的项，其余保持 Boot/池的默认。
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
