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

    /**
     * 撤销列表查不到时的响应体。
     *
     * <p>三处修正（都踩过）：
     * <ul>
     *   <li>以前的 {@code code} 写的是 500，而 HTTP 状态是 503 —— 前端按 code 判类型会看错档；</li>
     *   <li>以前没有任何线索，用户只能看到「莫名其妙 503」。现在写清是**哪一环**断了，
     *       并给出下一步（这条链路最常见的故障是「跨公网连 Redis + 500ms 超时」）；</li>
     *   <li>以前这里同时兜住了**下游路由**的异常（见 {@link #filter}），
     *       于是「Nacos 里没有 ai-service 实例」也被报成「会话校验服务不可用」——
     *       日志与响应一起指向 Redis，而 Redis 其实是好的。</li>
     * </ul>
     */
    private static final String UNAVAILABLE_BODY =
            "{\"code\":503,\"msg\":\"会话校验服务不可用：网关连不上 Redis 撤销列表\","
                    + "\"hint\":\"检查 REDIS_HOST/REDIS_PORT 是否可达与超时设置；"
                    + "本地开发建议指向本机 Redis（跨公网的 500ms 超时会让带 token 的请求随机 503）\"}";

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
        String path = exchange.getRequest().getPath().value();

        // ⚠️ 错误处理**只能包住这一次 Redis 查询**：把它挂在 flatMap 之前。
        // 以前写在整条链的最后，于是 chain.filter(exchange) 里抛出的任何异常
        // （最典型的是「Unable to find instance for xxx」）都会被这里吞掉并改写成
        // 「Redis 会话撤销校验失败」—— 排查方向被彻底带偏。
        Mono<Boolean> revoked = redisTemplate.hasKey(TokenRevocationKey.of(token))
                .doOnError(ex -> log.error("Redis 撤销列表查询失败 path={} error={}", path, ex.toString()))
                .onErrorMap(SessionCheckUnavailableException::new);

        return revoked
                .flatMap(value -> Boolean.TRUE.equals(value)
                        ? write(exchange, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY)
                        : chain.filter(exchange))
                .onErrorResume(SessionCheckUnavailableException.class,
                        ex -> write(exchange, HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_BODY));
    }

    /** 只用于区分「Redis 这一次查询失败」与「下游路由失败」，不对外暴露。 */
    static class SessionCheckUnavailableException extends RuntimeException {

        SessionCheckUnavailableException(Throwable cause) {
            super(cause);
        }
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
