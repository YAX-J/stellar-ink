package com.stellarink.gateway.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.sharedmodel.enums.Role;
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
 * <p><b>角色门槛（登录后）</b>：{@link Role#AUTHOR} 可写文章/流星；
 * {@link Role#ADMIN} 可审友链、调整用户角色。角色读自 JWT 的 {@code role} extra 字段。
 *
 * <p><b>身份传递</b>：网关不注入任何自定义身份头。下游各服务用同一 JWT 密钥
 * 独立验签后通过 {@code AuthHelper.loginId()}/{@code AuthHelper.currentRole()} 取用户信息（见 common-core）。
 */
@Configuration
public class SaTokenConfigure {

    /** 公开写接口：文章点赞（发光） */
    private static final Pattern GLOW_PATH = Pattern.compile("^/posts/\\d+/glow$");

    /** 文章单条路径：/posts/{id}（PUT 更新 / DELETE 删除，需 AUTHOR） */
    private static final Pattern POST_ID_PATH = Pattern.compile("^/posts/\\d+$");

    /** 流星单条路径：/meteors/{id}（DELETE 删除，需 AUTHOR） */
    private static final Pattern METEOR_ID_PATH = Pattern.compile("^/meteors/\\d+$");

    /** 友链审核：/links/{id}/status（需 ADMIN） */
    private static final Pattern LINK_STATUS_PATH = Pattern.compile("^/links/\\d+/status$");

    /** 用户角色调整：/user/{id}/role（需 ADMIN） */
    private static final Pattern USER_ROLE_PATH = Pattern.compile("^/user/\\d+/role$");

    private static final String MY_POSTS_PATH = "/posts/mine";

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
                    // 2) 管理端读接口（需 ADMIN，须先于「GET 全放行」判断）
                    if ("GET".equalsIgnoreCase(method) && "/user/list".equals(path)) {
                        requireRole(Role.ADMIN);
                        return;
                    }
                    // 3) 草稿只允许作者及以上读取，须先于「GET 全放行」判断
                    if ("GET".equalsIgnoreCase(method) && MY_POSTS_PATH.equals(path)) {
                        requireRole(Role.AUTHOR);
                        return;
                    }
                    // 4) 读请求与 CORS 预检放行
                    if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                            || "OPTIONS".equalsIgnoreCase(method)) {
                        return;
                    }
                    // 5) 公开写接口白名单
                    if ("POST".equalsIgnoreCase(method) && isPublicWrite(path)) {
                        return;
                    }
                    // 6) 其余一律要求登录（含 /actuator/** 写操作与任何未列举路径）
                    StpUtil.checkLogin();

                    // 7) 角色门槛（登录后，按写操作细分）
                    Role role = Role.parseOrDefault(String.valueOf(StpUtil.getExtra(Role.JWT_KEY)));
                    if (requiresAdmin(method, path) && !role.atLeast(Role.ADMIN)) {
                        throw new NotRoleException(Role.ADMIN.name());
                    }
                    if (requiresAuthor(method, path) && !role.atLeast(Role.AUTHOR)) {
                        throw new NotRoleException(Role.AUTHOR.name());
                    }
                })
                .setError(e -> {
                    // 统一返回 JSON + 正确 HTTP 状态：角色不足 403，未登录等 401
                    // （默认 writeResult 是 text/plain + SaResult.toString() 的 Map 格式，前端无法解析，故显式覆写）
                    SaHolder.getResponse().setHeader("Content-Type", "application/json;charset=UTF-8");
                    if (e instanceof NotRoleException) {
                        SaHolder.getResponse().setStatus(403);
                        return "{\"code\":403,\"msg\":\"权限不足：该操作需要更高角色\"}";
                    }
                    SaHolder.getResponse().setStatus(401);
                    return "{\"code\":401,\"msg\":\"未登录或登录已过期\"}";
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

    /** 需 ADMIN 的写操作：友链审核、用户角色调整 */
    private static boolean requiresAdmin(String method, String path) {
        if (!"PUT".equalsIgnoreCase(method)) {
            return false;
        }
        return LINK_STATUS_PATH.matcher(path).matches()
                || USER_ROLE_PATH.matcher(path).matches();
    }

    private static void requireRole(Role required) {
        StpUtil.checkLogin();
        Role current = Role.parseOrDefault(String.valueOf(StpUtil.getExtra(Role.JWT_KEY)));
        if (!current.atLeast(required)) {
            throw new NotRoleException(required.name());
        }
    }

    /** 需 AUTHOR（含 ADMIN）的写操作：文章/流星的新增与维护 */
    private static boolean requiresAuthor(String method, String path) {
        if ("POST".equalsIgnoreCase(method)) {
            return "/posts".equals(path) || "/meteors".equals(path);
        }
        if ("PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
            return POST_ID_PATH.matcher(path).matches()
                    || ("DELETE".equalsIgnoreCase(method) && METEOR_ID_PATH.matcher(path).matches());
        }
        return false;
    }

    /** JWT 无状态模式：token 自包含签名，各服务用相同密钥独立验签 */
    @Bean
    public StpLogicJwtForStateless getStpLogic() {
        return new StpLogicJwtForStateless();
    }
}
