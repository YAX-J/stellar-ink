package com.stellarink.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关错误出口：**503 必须说清是哪一环断的**。
 *
 * <p>这条守的是用户反馈过的一句话：「总是莫名其妙报 503」。该链路上 503 有三个来源
 * （服务没注册 / Redis 不可达 / 下游自己 503），只回状态码而不回原因，就只能靠翻日志猜。
 */
class GatewayErrorHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final GatewayErrorHandler handler = new GatewayErrorHandler(MAPPER);

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path));
    }

    private static JsonNode bodyOf(MockServerWebExchange exchange) throws Exception {
        String body = exchange.getResponse().getBodyAsString().block();
        assertNotNull(body, "响应体不能是空的 —— 空体就是「莫名其妙」的根源");
        return MAPPER.readTree(body);
    }

    @Test
    @DisplayName("Nacos 里没有实例：503 + 指名是哪个服务 + 给出排查方向")
    void missingInstanceSaysWhichService() throws Exception {
        MockServerWebExchange exchange = exchange("/ai/admin/providers");

        handler.handle(exchange, new NotFoundException("Unable to find instance for ai-service")).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        JsonNode body = bodyOf(exchange);
        assertEquals(503, body.get("code").asInt());
        assertTrue(body.get("msg").asText().contains("ai-service"), "要说清是哪个服务没找到：" + body);
        assertTrue(body.get("hint").asText().contains("Nacos"), "要给出下一步（Nacos/命名空间）");
        assertEquals("/ai/admin/providers", body.get("path").asText());
    }

    @Test
    @DisplayName("认不出服务名时也不空着：给通用结论而不是裸状态码")
    void unknownInstanceMessageStillExplains() throws Exception {
        MockServerWebExchange exchange = exchange("/posts");

        handler.handle(exchange, new NotFoundException("no upstream")).block();

        JsonNode body = bodyOf(exchange);
        assertEquals(503, body.get("code").asInt());
        assertTrue(body.get("msg").asText().contains("下游服务"));
        assertTrue(body.get("hint").asText().contains("Nacos"));
    }

    @Test
    @DisplayName("其它状态异常保留原状态码，且不把内部细节写进响应")
    void otherStatusExceptionsKeepTheirStatus() throws Exception {
        MockServerWebExchange exchange = exchange("/user/profile");

        handler.handle(exchange, new org.springframework.web.server.ResponseStatusException(
                HttpStatus.FORBIDDEN, "内部细节：角色表未命中")).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        String raw = exchange.getResponse().getBodyAsString().block();
        assertTrue(raw.contains("403"));
        assertTrue(!raw.contains("角色表未命中"), "上游 reason 属于内部细节，只进日志");
    }

    @Test
    @DisplayName("未预期异常：500 + 通用文案，绝不带栈")
    void unexpectedErrorsAreGeneric() throws Exception {
        MockServerWebExchange exchange = exchange("/posts/1");

        handler.handle(exchange, new IllegalStateException("jdbc://10.0.0.5:3306 refused")).block();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        String raw = exchange.getResponse().getBodyAsString().block();
        assertTrue(raw.contains("网关内部错误"));
        assertTrue(!raw.contains("10.0.0.5"), "内部地址不得出现在响应里：" + raw);
    }

    @Test
    @DisplayName("响应已开始（SSE 流）：不再改状态码，把错误交回上游")
    void committedResponseIsLeftAlone() {
        MockServerWebExchange exchange = exchange("/ai/qa/stream");
        exchange.getResponse().setComplete().block();

        // 已提交后 handle 应把错误原样抛出，而不是试图再写一个 503 覆盖流
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> handler.handle(exchange, new NotFoundException("Unable to find instance for ai-service"))
                        .block());
    }

    @Test
    @DisplayName("响应体是 UTF-8 JSON：中文提示不能变成问号")
    void bodyIsUtf8Json() throws Exception {
        MockServerWebExchange exchange = exchange("/ai/qa");

        handler.handle(exchange, new NotFoundException("Unable to find instance for ai-service")).block();

        byte[] bytes = exchange.getResponse().getBodyAsString()
                .map(text -> text.getBytes(StandardCharsets.UTF_8))
                .block();
        assertNotNull(bytes);
        JsonNode body = MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
        assertTrue(body.get("msg").asText().startsWith("网关找不到"), body.toString());
    }
}
