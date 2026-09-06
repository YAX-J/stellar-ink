package com.stellarink.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 路由：按业务域前缀转发到注册中心对应服务（lb 负载均衡）。
 * 对外 API 路径与单体时期保持一致，前端无感。
 */
@Configuration
public class RouteConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("user-service", r -> r.path("/auth/**", "/user/**")
                        .uri("lb://user-service"))
                .route("post-service", r -> r.path("/posts/**", "/tags/**", "/search/**")
                        .uri("lb://post-service"))
                .route("meteor-service", r -> r.path("/meteors/**")
                        .uri("lb://meteor-service"))
                .route("echo-service", r -> r.path("/echos/**")
                        .uri("lb://echo-service"))
                .route("link-service", r -> r.path("/links/**")
                        .uri("lb://link-service"))
                .route("stats-service", r -> r.path("/stats/**")
                        .uri("lb://stats-service"))
                .build();
    }
}
