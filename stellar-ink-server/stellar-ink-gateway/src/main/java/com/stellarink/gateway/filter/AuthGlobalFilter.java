package com.stellarink.gateway.filter;

import com.stellarink.common.constant.CommonConstants;
import com.stellarink.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 网关层统一鉴权：
 * - GET/OPTIONS、/auth/**（登录）、公开写接口（回声投瓶、友链申请、文章 glow）直接放行
 * - 其余对 /posts、/meteors、/links、/user 的写请求要求有效 JWT，
 *   校验通过后剥离客户端伪造的 X-User-Id，改以网关注入的可信用户头转发下游
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Pattern GLOW_PATH = Pattern.compile("^/posts/\\d+/glow$");

    private final JwtUtil jwtUtil;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpMethod method = request.getMethod();
        String path = request.getPath().value();

        if (method == HttpMethod.OPTIONS || method == HttpMethod.GET
                || path.startsWith("/auth") || isPublicWrite(method, path)) {
            return chain.filter(exchange);
        }

        boolean protectedScope = path.startsWith("/posts") || path.startsWith("/meteors")
                || path.startsWith("/links") || path.startsWith("/user");
        if (!protectedScope) {
            return chain.filter(exchange);
        }

        String header = request.getHeaders().getFirst(CommonConstants.AUTH_HEADER);
        Long userId = null;
        if (StringUtils.hasText(header) && header.startsWith(CommonConstants.TOKEN_PREFIX)) {
            userId = jwtUtil.parseUserId(header.substring(CommonConstants.TOKEN_PREFIX.length()));
        }
        if (userId == null) {
            log.warn("未授权的写请求 {} {} from {}", method, path,
                    request.getRemoteAddress() == null ? "unknown" : request.getRemoteAddress().getAddress().getHostAddress());
            return unauthorized(exchange);
        }

        ServerHttpRequest mutated = request.mutate()
                .headers(h -> h.remove(CommonConstants.GATEWAY_USER_HEADER))
                .header(CommonConstants.GATEWAY_USER_HEADER, String.valueOf(userId))
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isPublicWrite(HttpMethod method, String path) {
        return HttpMethod.POST.equals(method)
                && (path.equals("/echos") || path.equals("/links") || GLOW_PATH.matcher(path).matches());
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        DataBuffer buffer = response.bufferFactory()
                .wrap("{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}".getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -10;
    }
}
