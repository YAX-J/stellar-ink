package com.stellarink.gateway.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.util.SaResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.regex.Pattern;

/**
 * Sa-Token 网关鉴权配置（JWT 无状态模式）
 *
 * <p><b>策略：默认拒绝 + 显式白名单。</b>除下列放行项外，一切请求都要求有效 token。
 * 不要反向写成「默认放行、按前缀挑着拦」——那种写法只要出现一个未列举的路径前缀
 * （例如服务发现自动路由生成的 {@code /post-service/**}）就会静默失去保护。
 *
 * <p>放行项：
 * <ul>
 *   <li>{@code POST /auth/login}：登录本身</li>
 *   <li>{@code POST /auth/register}：开放注册（注册即登录，无需先有 token）</li>
 *   <li>GET / HEAD / OPTIONS：读请求与 CORS 预检</li>
 *   <li>公开写接口白名单：回声投瓶、友链申请、文章 glow（见 {@link #isPublicWrite}）</li>
 * </ul>
 *
 * <p><b>身份传递</b>：网关不注入任何自定义身份头。下游各服务用同一 JWT 密钥
 * 独立验签后通过 {@code AuthHelper.loginId()} 取用户 id（见 common-core）。
 */
@Configuration
public class SaTokenConfigure {

    /** 公开写接口：文章点赞（发光） */
    private static final Pattern GLOW_PATH = Pattern.compile("^/posts/\\d+/glow$");

    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/**")
                .setAuth(obj -> {
                    var request = SaHolder.getRequest();
                    String method = request.getMethod();
                    String path = request.getRequestPath();

                    // 1) 登录 / 注册接口：否则拿不到 token
                    if ("POST".equalsIgnoreCase(method)
                            && ("/auth/login".equals(path) || "/auth/register".equals(path))) {
                        return;
                    }
                    // 2) 读请求与 CORS 预检放行
                    if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                            || "OPTIONS".equalsIgnoreCase(method)) {
                        return;
                    }
                    // 3) 公开写接口白名单
                    if ("POST".equalsIgnoreCase(method) && isPublicWrite(path)) {
                        return;
                    }
                    // 4) 其余一律要求登录（含 /actuator/** 写操作与任何未列举路径）
                    StpUtil.checkLogin();
                })
                .setError(e -> {
                    // 未登录等鉴权异常统一返回 401 语义
                    return SaResult.code(401).setMsg("未登录或登录已过期");
                });
    }

    /**
     * 公开写接口白名单。
     * <p>必须精确匹配路径——安全判断一律不用 {@code startsWith}/{@code contains}，
     * 否则 {@code /post-service/posts} 这类路径会误判为公开。
     */
    private static boolean isPublicWrite(String path) {
        return "/echos".equals(path)
                || "/links".equals(path)
                || GLOW_PATH.matcher(path).matches();
    }

    /** JWT 无状态模式：token 自包含签名，各服务用相同密钥独立验签 */
    @Bean
    public StpLogicJwtForStateless getStpLogic() {
        return new StpLogicJwtForStateless();
    }
}
