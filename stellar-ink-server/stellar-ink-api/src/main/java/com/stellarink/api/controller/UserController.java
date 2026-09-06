package com.stellarink.api.controller;

import com.stellarink.common.constant.CommonConstants;
import com.stellarink.common.result.Result;
import com.stellarink.domain.dto.UserUpdateDTO;
import com.stellarink.domain.vo.UserVO;
import com.stellarink.service.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
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
    public Result<UserVO> profile(HttpServletRequest request) {
        return Result.ok(userService.profile(currentUserId(request)));
    }

    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@RequestBody UserUpdateDTO dto, HttpServletRequest request) {
        return Result.ok(userService.updateProfile(currentUserId(request), dto));
    }

    private Long currentUserId(HttpServletRequest request) {
        return (Long) request.getAttribute(CommonConstants.CURRENT_USER_ID);
    }
}
