package com.stellarink.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.dto.user.ChangePasswordDTO;
import com.stellarink.sharedmodel.dto.user.UserUpdateDTO;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.user.UserVO;
import com.stellarink.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    public Response<UserVO> profile() {
        return Response.success(userService.profile(AuthHelper.loginId()));
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
}
