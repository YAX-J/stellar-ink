package com.stellarink.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.sharedmodel.dto.user.LoginDTO;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.user.LoginVO;
import com.stellarink.sharedmodel.vo.user.UserVO;
import com.stellarink.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /** 登录：Sa-Token JWT 无状态登录，返回 tokenName/tokenValue 供网关与各服务验签 */
    @PostMapping("/login")
    public Response<LoginVO> login(@RequestBody LoginDTO dto) {
        UserVO user = userService.login(dto);
        LoginVO vo = new LoginVO(StpUtil.getTokenName(), StpUtil.getTokenValue(), user);
        return Response.success(vo);
    }
}
