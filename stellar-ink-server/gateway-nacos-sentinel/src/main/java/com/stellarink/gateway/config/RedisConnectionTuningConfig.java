package com.stellarink.gateway.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

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
 */
@Configuration
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
}
