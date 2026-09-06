package com.stellarink.api.advice;

import com.stellarink.common.exception.BusinessException;
import com.stellarink.common.result.Result;
import com.stellarink.common.result.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e, HttpServletResponse response) {
        log.warn("业务异常 code={} message={}", e.getCode(), e.getMessage());
        // 4xx/5xx 业务码同步为 HTTP 状态码，便于网关与监控识别
        if (e.getCode() >= 400 && e.getCode() < 600) {
            response.setStatus(e.getCode());
        }
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e, HttpServletResponse response) {
        log.error("unexpected error", e);
        response.setStatus(500);
        return Result.fail(ResultCode.SERVER_ERROR);
    }
}
