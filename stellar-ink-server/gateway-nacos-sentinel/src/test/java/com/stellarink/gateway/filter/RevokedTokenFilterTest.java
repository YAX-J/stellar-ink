package com.stellarink.gateway.filter;

import com.stellarink.sharedmodel.auth.TokenRevocationKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RevokedTokenFilterTest {

    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
    private final WebFilterChain chain = mock(WebFilterChain.class);
    private final RevokedTokenFilter filter = new RevokedTokenFilter(redisTemplate);

    @Test
    void rejectsRevokedTokenBeforeRouting() {
        String token = "revoked.jwt";
        var exchange = exchange("GET", "/user/profile", token);
        when(redisTemplate.hasKey(TokenRevocationKey.of(token))).thenReturn(Mono.just(true));

        filter.filter(exchange, chain).block();

        org.assertj.core.api.Assertions.assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(exchange);
    }

    @Test
    void allowsTokenThatIsNotRevoked() {
        String token = "active.jwt";
        var exchange = exchange("GET", "/user/profile", token);
        when(redisTemplate.hasKey(TokenRevocationKey.of(token))).thenReturn(Mono.just(false));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    @Test
    void loginDoesNotDependOnRevocationStore() {
        var exchange = exchange("POST", "/auth/login", "expired.jwt");
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(redisTemplate, never()).hasKey(org.mockito.ArgumentMatchers.anyString());
        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("Redis 查不通：503 + 说清是会话校验这一环 + code 与状态码一致")
    void redisFailureExplainsWhichLinkBroke() {
        String token = "some.jwt";
        var exchange = exchange("GET", "/user/profile", token);
        when(redisTemplate.hasKey(TokenRevocationKey.of(token)))
                .thenReturn(Mono.error(new org.springframework.dao.QueryTimeoutException("Redis command timed out")));

        filter.filter(exchange, chain).block();

        org.assertj.core.api.Assertions.assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        String body = exchange.getResponse().getBodyAsString().block();
        // 以前这里写的是 code:500 配 HTTP 503，前端按 code 判类型会看错档
        org.assertj.core.api.Assertions.assertThat(body).contains("\"code\":503");
        org.assertj.core.api.Assertions.assertThat(body).contains("Redis");
        org.assertj.core.api.Assertions.assertThat(body).contains("hint");
        verify(chain, never()).filter(exchange);
    }

    @Test
    @DisplayName("下游路由失败不能被改写成「会话校验不可用」")
    void downstreamFailureIsNotBlamedOnRedis() {
        String token = "active.jwt";
        var exchange = exchange("GET", "/ai/admin/providers", token);
        when(redisTemplate.hasKey(TokenRevocationKey.of(token))).thenReturn(Mono.just(false));
        // Nacos 里没有实例时，路由会抛这个 —— 它与 Redis 毫无关系
        var routingFailure = new org.springframework.cloud.gateway.support.NotFoundException(
                "Unable to find instance for ai-service");
        when(chain.filter(exchange)).thenReturn(Mono.error(routingFailure));

        // 错误必须原样穿出去（交给 GatewayErrorHandler 渲染成「找不到实例」），
        // 而不是被这个过滤器吞成「会话校验服务不可用」——那会把排查方向带偏到 Redis
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.cloud.gateway.support.NotFoundException.class,
                () -> filter.filter(exchange, chain).block());
        org.assertj.core.api.Assertions.assertThat(exchange.getResponse().getStatusCode())
                .isNotEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("一次网络抖动不该把请求判成 503：重试一次成功后照常放行")
    void transientRedisFailureIsRetriedBeforeFailingClosed() {
        String token = "active.jwt";
        var exchange = exchange("GET", "/posts", token);
        // ⚠️ 必须用 Mono.defer：Reactor 的 retry 是**重新订阅那个 Mono**，而不是重新调用 hasKey()。
        // 真实模板返回的是「每次订阅都真发一次命令」的冷 Mono，defer 才等价地模拟了这一点；
        // 若直接 thenReturn(Mono.error(...))，重试只会再订阅同一个错误，测不到任何东西。
        AtomicInteger attempts = new AtomicInteger();
        when(redisTemplate.hasKey(TokenRevocationKey.of(token))).thenAnswer(invocation -> Mono.defer(
                () -> attempts.incrementAndGet() == 1
                        // 第一次：连接被掐断 / 正在重连 —— 远端 Redis 的典型抖动
                        ? Mono.error(new org.springframework.dao.QueryTimeoutException("Redis command timed out"))
                        : Mono.just(false)));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        org.assertj.core.api.Assertions.assertThat(attempts.get())
                .as("应当重试一次")
                .isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(exchange.getResponse().getStatusCode())
                .as("重试成功后不该是 503")
                .isNotEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("两次都失败才 fail-closed：503 且带可读原因")
    void persistentRedisFailureStillFailsClosed() {
        String token = "active.jwt";
        var exchange = exchange("GET", "/posts", token);
        AtomicInteger attempts = new AtomicInteger();
        when(redisTemplate.hasKey(TokenRevocationKey.of(token))).thenAnswer(invocation -> Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new org.springframework.dao.QueryTimeoutException("Redis command timed out"));
        }));

        filter.filter(exchange, chain).block();

        org.assertj.core.api.Assertions.assertThat(attempts.get())
                .as("重试过一次才放弃")
                .isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(chain, never()).filter(exchange);
    }

    @Test
    @DisplayName("Redis 一直不返回（池被占满 / 连接卡死）：2s 内快速失败，而不是让浏览器干等 15s")
    void hangingRedisLookupFailsFast() {
        String token = "active.jwt";
        var exchange = exchange("GET", "/ai/admin/models", token);
        // Mono.never() = 命令发出去石沉大海：响应式池 acquire 没有超时（max-wait 不会被搬过去），
        // 没有这一层上限时请求会一直挂着，用户看到的只是浏览器自己的「请求超时」
        when(redisTemplate.hasKey(TokenRevocationKey.of(token))).thenReturn(Mono.never());

        long startedAt = System.nanoTime();
        filter.filter(exchange, chain).block();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        org.assertj.core.api.Assertions.assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        org.assertj.core.api.Assertions.assertThat(elapsedMillis)
                .as("应当在下单次请求可接受的范围内快速失败")
                .isLessThan(5_000L);
        verify(chain, never()).filter(exchange);
    }

    private MockServerWebExchange exchange(String method, String path, String token) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(
                        org.springframework.http.HttpMethod.valueOf(method), path)
                .header(HttpHeaders.AUTHORIZATION, token));
    }
}
