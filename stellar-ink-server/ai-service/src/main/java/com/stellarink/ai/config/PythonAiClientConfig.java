package com.stellarink.ai.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
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
 */
@Configuration
@EnableFeignClients(basePackages = "com.stellarink.aiclient.client")
public class PythonAiClientConfig {
}
