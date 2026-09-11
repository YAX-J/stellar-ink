package com.stellarink.common.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;

/**
 * 认证工具：从 Sa-Token JWT 中获取当前登录用户 id 与角色
 * （网关已完成登录校验，服务内使用同一密钥独立验签）
 */
public final class AuthHelper {

    private AuthHelper() {
    }

    public static Long loginId() {
        return StpUtil.getLoginIdAsLong();
    }

    /** 当前用户角色（无法识别时回退 READER，最低权限） */
    public static Role currentRole() {
        return Role.parseOrDefault(String.valueOf(StpUtil.getExtra(Role.JWT_KEY)));
    }

    /** 要求当前用户至少达到指定角色，否则抛 403（服务内防御性校验，主门槛在网关） */
    public static void requireAtLeast(Role required) {
        if (!currentRole().atLeast(required)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "权限不足，无法执行该操作。");
        }
    }
}
