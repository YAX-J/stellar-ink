package com.stellarink.gateway.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网关的错误出口：**把「哪一环断了」写进响应体**。
 *
 * <p>为什么需要它：Spring Cloud Gateway 在「Nacos 里找不到实例」时只回一个**空的 503**
 * （日志里是 {@code NotFoundException: 503 SERVICE_UNAVAILABLE "Unable to find instance for ai-service"}），
 * 前端只能显示「服务不可用」。而这条链路上 503 有三个完全不同的来源：
 * ① 服务没启动 / 没注册进 Nacos；② Redis 不可达导致撤销校验 fail-closed（见 {@code RevokedTokenFilter}）；
 * ③ 下游服务自己返回 503。同一个状态码、三种处置方式，不给线索就只能靠翻日志猜 ——
 * 这正是「总是莫名其妙 503」的来源。
 *
 * <p>顺序 {@code @Order(-2)}：必须先于 Spring Boot 的 {@code DefaultErrorWebExceptionHandler}，
 * 否则它会把响应渲染成 Boot 的默认结构（{@code timestamp/status/error/path}），
 * 与全站契约 {@code {code,msg,traceId}} 不一致。
 */
@Slf4j
@Component
@Order(-2)
@RequiredArgsConstructor
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    /** 从 `Unable to find instance for ai-service` 里取出服务名 */
    private static final Pattern MISSING_INSTANCE = Pattern.compile("Unable to find instance for (\\S+)");

    private static final String INSTANCE_HINT =
            "确认该服务已启动、已注册到 Nacos，且与网关在同一 NAMESPACE（本地还要确认 8848 在跑）；"
                    + "服务刚重启时会有十几秒的注册窗口，稍等重试即可";

    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            // 已经开始写响应了（例如 SSE 流）：只能把错误交回上游，不能再改状态码
            return Mono.error(error);
        }

        HttpStatusCode status;
        String message;
        String hint = null;
        if (error instanceof NotFoundException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            String service = missingServiceOf(error.getMessage());
            message = service == null
                    ? "网关找不到下游服务的可用实例"
                    : "网关找不到下游服务的可用实例：" + service;
            hint = INSTANCE_HINT;
            log.error("路由失败（无可用实例）：path={} error={}", pathOf(exchange), error.getMessage());
        } else if (error instanceof ResponseStatusException statusException) {
            status = statusException.getStatusCode();
            message = "请求未能完成（HTTP " + status.value() + "）";
            log.warn("网关响应状态异常：path={} status={} reason={}",
                    pathOf(exchange), status.value(), statusException.getReason());
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "网关内部错误，请稍后重试";
            // 内部异常只进日志：message 里可能有主机名、栈等细节
            log.error("网关未捕获异常：path={}", pathOf(exchange), error);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("msg", message);
        if (hint != null) {
            body.put("hint", hint);
        }
        body.put("path", pathOf(exchange));

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception serializationFailure) {
            // 序列化都失败了就别再抛：给一句能读的话，保证响应不是空的
            bytes = "{\"code\":500,\"msg\":\"网关内部错误\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private static String missingServiceOf(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = MISSING_INSTANCE.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String pathOf(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value();
    }
}
