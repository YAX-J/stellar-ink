package com.stellarink.common.auth;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;

/**
 * 认证工具：从 Sa-Token JWT 中获取当前登录用户 id 与角色
 * （网关已完成登录校验，服务内使用同一密钥独立验签）
 *
 * <p><b>为什么这里要兜住 {@link SaTokenException}</b>：网关对 GET 一律放行，读接口的登录校验
 * 实际落在服务内部，此时请求头里可能压根没有 token；而 JWT 无状态模式下读取 extra（角色）
 * 会直接抛 {@code SaJwtException: jwt 字符串不可为空}。若让它在业务代码里冒泡：
 * <ul>
 *   <li>前端拿到的是 500「系统繁忙」，而不是约定的 <b>HTTP 200 + code 401</b>，
 *       于是只显示「读取失败」、不会跳登录（本项目已踩过一次同类问题）；</li>
 *   <li>日志里出现大量「未预期异常」，把真正的故障淹没。</li>
 * </ul>
 * 在唯一入口翻译成 {@link ErrorCode#UNAUTHORIZED}，所有服务的鉴权行为自动一致。
 */
public final class AuthHelper {

    private AuthHelper() {
    }

    public static Long loginId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (SaTokenException e) {
            throw unauthorized(e);
        }
    }

    /** 当前用户角色（无法识别时回退 READER，最低权限） */
    public static Role currentRole() {
        try {
            return Role.parseOrDefault(String.valueOf(StpUtil.getExtra(Role.JWT_KEY)));
        } catch (SaTokenException e) {
            throw unauthorized(e);
        }
    }

    /** 要求当前用户至少达到指定角色，否则抛 403（服务内防御性校验，主门槛在网关） */
    public static void requireAtLeast(Role required) {
        if (!currentRole().atLeast(required)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "权限不足，无法执行该操作。");
        }
    }

    /** 把 Sa-Token 的各类鉴权异常统一成 401（消息不暴露内部细节，只说明需要重新登录）。 */
    private static BusinessException unauthorized(SaTokenException cause) {
        return new BusinessException(ErrorCode.UNAUTHORIZED,
                "登录状态已失效，请重新登录。" + "（" + cause.getClass().getSimpleName() + "）");
    }
}
