package com.stellarink.gateway.filter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class AccountVersionFilterTest {
    @Test
    void deniesOldTokenAndFailsClosedOnBrokenOrEmptyContract() {
        for (String body : new String[]{"{\"code\":0,\"data\":false}", "{\"code\":500}", "", "bad-json"}) {
            var filter = filter(Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json").body(body).build()));
            var exchange = exchange();
            AtomicBoolean called = new AtomicBoolean();
            filter.filter(exchange, ignored -> { called.set(true); return Mono.empty(); }).block();
            assertThat(called).isFalse();
            assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(body.contains("false") ? 401 : 503);
        }
    }

    @Test
    void allowsCurrentVersionWithoutReclassifyingDownstreamError() {
        var filter = filter(Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json").body("{\"code\":0,\"data\":true}").build()));
        assertThatThrownBy(() -> filter.filter(exchange(), ignored -> Mono.error(new IllegalStateException("业务异常"))).block())
                .isInstanceOf(IllegalStateException.class).hasMessage("业务异常");
    }

    @Test
    void timesOutAndLeavesAnonymousAndLoginPathsAvailable() {
        var filter = filter(Mono.never());
        var exchange = exchange();
        filter.filter(exchange, ignored -> Mono.empty()).block(Duration.ofSeconds(4));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        for (var request : new MockServerHttpRequest.BaseBuilder<?>[]{
                MockServerHttpRequest.get("/posts"), MockServerHttpRequest.post("/auth/login").header("Authorization", "old")}) {
            AtomicBoolean called = new AtomicBoolean();
            filter.filter(MockServerWebExchange.from(request), ignored -> { called.set(true); return Mono.empty(); }).block();
            assertThat(called).isTrue();
        }
    }

    private AccountVersionFilter filter(Mono<ClientResponse> response) {
        return new AccountVersionFilter(WebClient.builder().exchangeFunction(request -> response), new SimpleMeterRegistry());
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/user/profile").header("Authorization", "token"));
    }
}
