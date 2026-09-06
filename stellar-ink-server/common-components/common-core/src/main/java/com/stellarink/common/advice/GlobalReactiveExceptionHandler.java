package com.stellarink.common.advice;

import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

import java.util.UUID;

/**
 * 全局异常处理类（Reactive 环境，供 WebFlux 网关使用）
 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@Slf4j
public class GlobalReactiveExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Response<?> handleBusiness(BusinessException e, ServerWebExchange exchange) {
        log.warn("业务异常: {}, 请求路径: {}", e.getMessage(), exchange.getRequest().getPath().value());
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return Response.error(e.getCode(), e.getMessage()).withTraceId(generateTraceId());
    }

    @ExceptionHandler(Exception.class)
    public Response<?> handleUnexpected(Exception e, ServerWebExchange exchange) {
        log.error("未预期异常: {}, 请求路径: {}", e.getMessage(), exchange.getRequest().getPath().value(), e);
        exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        return Response.error(ErrorCode.SYSTEM_ERROR).withTraceId(generateTraceId());
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
