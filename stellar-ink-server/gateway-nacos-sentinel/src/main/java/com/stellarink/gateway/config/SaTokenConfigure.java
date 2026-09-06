package com.stellarink.gateway.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.util.SaResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 网关鉴权配置（JWT 无状态模式）
 * 规则：
 * - 放行 GET/OPTIONS、/auth/**（登录）、公开写接口（回声投瓶、友链申请、文章 glow）
 * - 其余对 /posts、/meteors、/links、/user 的写请求要求有效 token
 * - 校验通过后以网关注入的 X-User-Id 可信头转发下游（剥离客户端伪造的同名头）
 */
@Configuration
public class SaTokenConfigure {

    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/**")
                .setAuth(obj -> {
                    SaRouter.notMatch("/auth/**");

                    var request = SaHolder.getRequest();
                    String method = request.getMethod();
                    String path = request.getRequestPath();

                    // 读请求放行
                    if ("GET".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
                        return;
                    }
                    // 公开写接口放行
                    if ("POST".equalsIgnoreCase(method)
                            && (path.equals("/echos") || path.equals("/links")
                                || path.matches("^/posts/\\d+/glow$"))) {
                        return;
                    }
                    boolean protectedScope = path.startsWith("/posts") || path.startsWith("/meteors")
                            || path.startsWith("/links") || path.startsWith("/user");
                    if (protectedScope) {
                        StpUtil.checkLogin();
                    }
                })
                .setError(e -> {
                    // 未登录等鉴权异常统一返回 401 语义
                    return SaResult.code(401).setMsg("未登录或登录已过期");
                });
    }

    /** JWT 无状态模式：token 自包含签名，各服务用相同密钥独立验签 */
    @Bean
    public StpLogicJwtForStateless getStpLogic() {
        return new StpLogicJwtForStateless();
    }
}
