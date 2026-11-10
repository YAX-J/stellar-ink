package com.stellarink.user.controller;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.sharedmodel.auth.AuthVersion;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.user.mapper.UserMapper;
import com.stellarink.user.pojo.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅容器内网可达，不配置网关路由；身份只来自验签后的 JWT，不接受用户 ID 参数。 */
@RestController
@RequiredArgsConstructor
public class InternalSessionController {
    private final UserMapper userMapper;

    @GetMapping("/internal/auth/session")
    public Response<Boolean> validate() {
        try {
            Long id = StpUtil.getLoginIdAsLong();
            Object version = StpUtil.getExtra(AuthVersion.JWT_KEY);
            Object role = StpUtil.getExtra(Role.JWT_KEY);
            // 不缓存版本：变更提交后到来的校验直接读主库，避免缓存失效竞态。
            User user = userMapper.selectById(id);
            return Response.success(user != null && AuthVersion.matches(version, user.getAuthVersion())
                    && user.getRole().equals(String.valueOf(role)));
        } catch (SaTokenException | NumberFormatException ex) {
            return Response.success(false);
        }
    }
}
