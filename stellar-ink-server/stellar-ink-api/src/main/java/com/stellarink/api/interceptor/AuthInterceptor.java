package com.stellarink.api.interceptor;

import com.stellarink.common.constant.CommonConstants;
import com.stellarink.common.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 写操作鉴权：登记的路径下放行 GET/OPTIONS，其余要求有效 JWT
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (HttpMethod.GET.matches(request.getMethod())
                || HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String header = request.getHeader(CommonConstants.AUTH_HEADER);
        if (header != null && header.startsWith(CommonConstants.TOKEN_PREFIX)) {
            Long userId = jwtUtil.parseUserId(header.substring(CommonConstants.TOKEN_PREFIX.length()));
            if (userId != null) {
                request.setAttribute(CommonConstants.CURRENT_USER_ID, userId);
                return true;
            }
        }
        log.warn("未授权的写请求 {} {} from {}", request.getMethod(), request.getRequestURI(),
                request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}");
        return false;
    }
}
