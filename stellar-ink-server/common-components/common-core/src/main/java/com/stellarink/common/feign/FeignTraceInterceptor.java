package com.stellarink.common.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 链路追踪：把当前请求的 traceId 透传到下游服务
 *
 * <p><b>不要在这里转发任何身份头。</b>本服务已移除 X-User-Id 转发：
 * 网关不注入身份头，若把客户端传入的 X-User-Id 原样转发，一旦下游有代码信任该头即成为越权漏洞。
 * 下游服务获取用户身份统一走 Sa-Token：用同一 JWT 密钥独立验签后调用
 * {@code AuthHelper.loginId()}。
 */
@Component
public class FeignTraceInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String traceId = request.getHeader("X-Trace-Id");
            if (traceId != null && !traceId.isBlank()) {
                template.header("X-Trace-Id", traceId);
            }
        }
    }
}
