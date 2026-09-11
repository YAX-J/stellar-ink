package com.stellarink.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.sharedmodel.dto.user.LoginDTO;
import com.stellarink.sharedmodel.dto.user.RegisterDTO;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.user.LoginVO;
import com.stellarink.sharedmodel.vo.user.UserVO;
import com.stellarink.user.service.UserService;
import jakarta.validation.Valid;
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
    public Response<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        UserVO user = userService.login(dto);
        LoginVO vo = new LoginVO(StpUtil.getTokenName(), StpUtil.getTokenValue(), user);
        return Response.success(vo);
    }

    /** 注册：开放注册，注册即登录，返回与登录相同的 token 结构 */
    @PostMapping("/register")
    public Response<LoginVO> register(@Valid @RequestBody RegisterDTO dto) {
        UserVO user = userService.register(dto);
        LoginVO vo = new LoginVO(StpUtil.getTokenName(), StpUtil.getTokenValue(), user);
        return Response.success(vo);
    }

    /**
     * 登出：JWT 无状态模式下无法在服务端吊销 token，
     * 此处仅作语义收口（要求携带有效 token 到达，网关已校验），前端丢弃 token 即可。
     */
    @PostMapping("/logout")
    public Response<Void> logout() {
        return Response.success();
    }
}
