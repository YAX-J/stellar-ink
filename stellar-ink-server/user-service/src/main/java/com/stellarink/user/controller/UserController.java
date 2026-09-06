package com.stellarink.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.dto.user.UserUpdateDTO;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.user.UserVO;
import com.stellarink.user.service.UserService;
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
    public Response<UserVO> updateProfile(@RequestBody UserUpdateDTO dto) {
        return Response.success(userService.updateProfile(AuthHelper.loginId(), dto));
    }
}
