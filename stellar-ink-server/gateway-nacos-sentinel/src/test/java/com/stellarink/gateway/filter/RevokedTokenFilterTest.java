package com.stellarink.gateway.filter;

import com.stellarink.sharedmodel.auth.TokenRevocationKey;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

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

    private MockServerWebExchange exchange(String method, String path, String token) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(
                        org.springframework.http.HttpMethod.valueOf(method), path)
                .header(HttpHeaders.AUTHORIZATION, token));
    }
}
