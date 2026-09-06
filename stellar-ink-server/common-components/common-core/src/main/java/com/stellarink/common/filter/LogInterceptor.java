package com.stellarink.common.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 访问日志拦截器：方法 路径 状态码 耗时
 */
@Slf4j
@Component
public class LogInterceptor implements HandlerInterceptor {

    private static final String ATTR_START = "logStartTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object start = request.getAttribute(ATTR_START);
        long cost = start == null ? -1 : System.currentTimeMillis() - (long) start;
        log.info("API-ACCESS {} {} {} {}ms",
                request.getMethod(), request.getRequestURI(), response.getStatus(), cost);
    }
}
