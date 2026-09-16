package com.stellarink.gateway.filter;

import com.stellarink.sharedmodel.auth.TokenRevocationKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** 在 Sa-Token 路由鉴权前拦截已登出或改密后撤销的 JWT。 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RevokedTokenFilter implements WebFilter {

    private static final String UNAUTHORIZED_BODY = "{\"code\":401,\"msg\":\"会话已失效，请重新登录\"}";
    private static final String UNAVAILABLE_BODY = "{\"code\":500,\"msg\":\"会话校验服务暂不可用\"}";

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isSessionCreation(exchange)) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(token)) {
            return chain.filter(exchange);
        }
        return redisTemplate.hasKey(TokenRevocationKey.of(token))
                .flatMap(revoked -> Boolean.TRUE.equals(revoked)
                        ? write(exchange, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY)
                        : chain.filter(exchange))
                .onErrorResume(ex -> {
                    log.error("Redis 会话撤销校验失败 path={} error={}",
                            exchange.getRequest().getPath().value(), ex.toString());
                    return write(exchange, HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_BODY);
                });
    }

    private boolean isSessionCreation(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return "POST".equalsIgnoreCase(exchange.getRequest().getMethod().name())
                && ("/auth/login".equals(path) || "/auth/register".equals(path));
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String body) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
