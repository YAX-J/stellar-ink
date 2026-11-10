package com.stellarink.gateway.filter;

import com.stellarink.sharedmodel.response.Response;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 在路由前复核账号版本；无缓存、无故障放行，不阻塞 Netty 线程。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class AccountVersionFilter implements WebFilter {
    private final WebClient client;
    private final MeterRegistry metrics;

    public AccountVersionFilter(@Qualifier("sessionWebClientBuilder") WebClient.Builder builder,
                                MeterRegistry metrics) {
        this.client = builder.build();
        this.metrics = metrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();
        if ("OPTIONS".equals(method) || ("POST".equals(method)
                && ("/auth/login".equals(path) || "/auth/register".equals(path)))) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(token)) return chain.filter(exchange);

        // 错误只包住内部校验，不能把业务接口错误误报为账号校验失败。
        Mono<Integer> verdict = client.get().uri("http://user-service/internal/auth/session")
                .header(HttpHeaders.AUTHORIZATION, token)
                .retrieve().bodyToMono(new ParameterizedTypeReference<Response<Boolean>>() { })
                .map(body -> {
                    if (!Integer.valueOf(0).equals(body.getCode())) return 503;
                    return Boolean.TRUE.equals(body.getData()) ? 200 : 401;
                })
                .defaultIfEmpty(503)
                .timeout(Duration.ofSeconds(2))
                .onErrorReturn(503);
        return verdict.flatMap(status -> {
            metrics.counter("stellar.auth.version.check", "result",
                    status == 200 ? "ok" : status == 401 ? "invalid" : "error").increment();
            if (status == 200) return chain.filter(exchange);
            exchange.getResponse().setStatusCode(HttpStatus.valueOf(status));
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            String message = status == 401 ? "账号凭据已变更，请重新登录" : "账号状态校验暂不可用，请稍后重试";
            byte[] body = ("{\"code\":" + status + ",\"msg\":\"" + message + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        });
    }
}
