package com.stellarink.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.dto.user.ChangePasswordDTO;
import com.stellarink.sharedmodel.dto.user.ChangeRoleDTO;
import com.stellarink.sharedmodel.dto.user.UserUpdateDTO;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.user.UserVO;
import com.stellarink.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    public Response<UserVO> profile() {
        return Response.success(userService.profile(AuthHelper.loginId()));
    }

    /** 用户列表（仅站长，供角色管理页枚举；网关已做 ADMIN 门槛 + 服务内防御性校验） */
    @GetMapping("/list")
    public Response<List<UserVO>> listUsers() {
        return Response.success(userService.listUsers(AuthHelper.loginId()));
    }

    @PutMapping("/profile")
    public Response<UserVO> updateProfile(@Valid @RequestBody UserUpdateDTO dto) {
        return Response.success(userService.updateProfile(AuthHelper.loginId(), dto));
    }

    /** 修改密码：校验旧密码后更新（需登录） */
    @PutMapping("/password")
    public Response<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        userService.changePassword(AuthHelper.loginId(), dto);
        return Response.success();
    }

    /** 调整用户角色（仅站长，网关已做 ADMIN 门槛 + 服务内防御性校验） */
    @PutMapping("/{id}/role")
    public Response<UserVO> changeRole(@PathVariable Long id, @Valid @RequestBody ChangeRoleDTO dto) {
        return Response.success(userService.changeRole(AuthHelper.loginId(), id, dto.getRole()));
    }
}
