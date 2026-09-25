package com.stellarink.ai.config;

import com.stellarink.aiclient.error.PythonErrorDecoder;
import feign.codec.ErrorDecoder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 客户端装配：把 Python 内部客户端契约（{@code com.stellarink.aiclient.client}）注册成 Bean。
 *
 * <p>单独成类而不是写在启动类上，是为了让「Java 调 Python 的入口」有一个明确的装配点，
 * 而不是散落在启动类的注解堆里；去掉它，生产环境调用 Python 会直接报「找不到 PythonAiClient」。
 *
 * <p>⚠️ 切片测试的注意点（踩过一次）：本模块的启动类**显式声明了 {@code @ComponentScan}**，
 * 而显式声明会让 Spring Boot 切片测试依赖的「类型排除过滤器」失效 —— 也就是说
 * {@code @WebMvcTest} 实际上会把 {@code com.stellarink.ai} 下的组件**全部**装配起来。
 * 于是切片里必须把外部依赖 `@MockBean` 掉：探活切片要 mock {@code PythonAiClient}
 * （否则会去建 Feign 的 FactoryBean，而 Feign 自动配置不在 Web 切片里，报
 * 「No qualifying bean of type FeignClientFactory」），评测切片还要 mock
 * {@code AiProviderConfigService}（模型配置要 Mapper，切片里没有库）。
 *
 * <p>签名头由 {@link InternalSignatureFeignInterceptor} 统一注入（它是 {@code RequestInterceptor} Bean，
 * 对上下文里所有 Feign 客户端生效），调用方不需要自己拼 {@code X-AI-*}。
 * 错误体由 {@code PythonErrorDecoder} 翻成可展示的业务异常（同样是全局 Bean）。
 */
@Configuration
@EnableFeignClients(basePackages = "com.stellarink.aiclient.client")
public class PythonAiClientConfig {

    /**
     * Python 错误体 → 可展示的业务异常。
     *
     * <p>不加这一条时，Python 刻意给出的可读错误（例如「角色 embedding 尚未配置模型」）
     * 会退化成一个裸的 {@code FeignException}，被全局处理器兜成
     * {@code code=500「系统繁忙，请稍后重试」} —— 实测确认过。
     *
     * <p>注册成普通 Bean 即可生效：Feign 子上下文以主上下文为父，与
     * {@code RequestInterceptor} 是同一套机制。
     */
    @Bean
    ErrorDecoder pythonErrorDecoder() {
        return new PythonErrorDecoder();
    }
}
