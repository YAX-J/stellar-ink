package com.stellarink.user.controller;

import com.stellarink.common.constant.CommonConstants;
import com.stellarink.common.exception.BusinessException;
import com.stellarink.common.result.Result;
import com.stellarink.common.result.ResultCode;
import com.stellarink.user.dto.UserUpdateDTO;
import com.stellarink.user.vo.UserVO;
import com.stellarink.user.service.UserService;
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

    /** 用户 id 由网关校验 JWT 后以可信请求头传入 */
    private Long currentUserId(HttpServletRequest request) {
        String header = request.getHeader(CommonConstants.GATEWAY_USER_HEADER);
        if (header == null || header.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请求未经过网关，缺少用户身份");
        }
        return Long.valueOf(header);
    }
}
