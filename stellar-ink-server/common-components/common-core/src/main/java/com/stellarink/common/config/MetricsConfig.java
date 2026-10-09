package com.stellarink.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 所有指标的公共标签：{@code application=<spring.application.name>}。
 *
 * <p><b>为什么必须有它</b>：Prometheus 从四个服务各抓一份指标，名字都一样
 * （{@code http_server_requests_seconds}、{@code jvm_memory_used_bytes}…）。
 * 没有这个标签，看板上「哪个服务慢」这个问题就只能靠 instance 猜 ——
 * 而 instance 是 IP:端口，容器重建就变。加上它之后，一条 PromQL 就能按服务切开：
 * {@code sum by (application) (rate(http_server_requests_seconds_count[5m]))}。
 *
 * <p><b>为什么用 MeterRegistryCustomizer 而不是在注册表上直接 config().commonTags()</b>：
 * 公共标签必须在**任何指标被注册之前**生效。JVM/进程指标由 Spring Boot 的
 * 自动配置在上下文早期就注册，等我们拿到 MeterRegistry 再补标签就晚了
 * —— 那些指标会永远少一个标签，而这件事**不会报错**，只会让看板里一部分曲线对不上。
 * Customizer 是由 Boot 在创建注册表时统一应用的，顺序上天然正确。
 *
 * <p><b>为什么本类在 common-core 里只对 user / content / ai 三个服务生效</b>：
 * 网关是 WebFlux 应用，启动类的组件扫描只覆盖 {@code com.stellarink.gateway}，
 * 不包含 {@code com.stellarink.common}（网关不能依赖带 servlet 的 common-core）。
 * 所以网关有一份等价实现：{@code com.stellarink.gateway.config.GatewayMetricsConfig}。
 * <b>改一处要同步另一处</b> —— 这与 {@code JceWarmupRunner}、{@code SecretGuard}
 * 在两侧各留一份是同一个理由。
 *
 * <p>用 {@code @ConditionalOnClass} 而不是硬依赖：消费方没引 actuator 时本类不装配，
 * 服务照常启动（代价只是没有指标），而不是启动失败。
 */
@Configuration
@ConditionalOnClass({MeterRegistry.class, MeterRegistryCustomizer.class})
public class MetricsConfig {

    /** 公共标签名。Prometheus 侧会原样出现为 {@code application="content-service"}。 */
    public static final String APPLICATION_TAG = "application";

    @Bean
    MeterRegistryCustomizer<MeterRegistry> stellarInkCommonTags(
            @Value("${spring.application.name:unknown}") String applicationName) {
        return registry -> registry.config().commonTags(APPLICATION_TAG, applicationName);
    }
}
