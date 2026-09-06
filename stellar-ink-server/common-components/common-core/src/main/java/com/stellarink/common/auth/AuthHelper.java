package com.stellarink.common.auth;

import cn.dev33.satoken.stp.StpUtil;

/**
 * 认证工具：从 Sa-Token JWT 中获取当前登录用户 id
 * （网关已完成登录校验，服务内使用同一密钥独立验签）
 */
public final class AuthHelper {

    private AuthHelper() {
    }

    public static Long loginId() {
        return StpUtil.getLoginIdAsLong();
    }
}
