package com.stellarink.gateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关所有指标的公共标签：{@code application=<spring.application.name>}。
 *
 * <p><b>为什么网关有一份自己的实现</b>：{@code common-core} 里有等价的
 * {@code MetricsConfig}，但网关是 WebFlux 应用、启动类的组件扫描只覆盖
 * {@code com.stellarink.gateway}，**不能**依赖带 servlet 的 common-core
 * （同 {@code JceWarmupRunner}、{@code SecretGuard} 在两侧各留一份的理由）。
 * <b>改一处必须同步另一处</b>，否则网关的曲线会在看板上因为没有 application 标签而消失。
 *
 * <p>标签名与 common-core 的 {@code MetricsConfig.APPLICATION_TAG} 必须逐字一致
 * （都是 {@code application}）。
 */
@Configuration
@ConditionalOnClass({MeterRegistry.class, MeterRegistryCustomizer.class})
public class GatewayMetricsConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> gatewayCommonTags(
            @Value("${spring.application.name:gateway-nacos-sentinel}") String applicationName) {
        return registry -> registry.config().commonTags("application", applicationName);
    }
}
