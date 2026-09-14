package com.stellarink.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.dto.user.ChangePasswordDTO;
import com.stellarink.sharedmodel.dto.user.ChangeRoleDTO;
import com.stellarink.sharedmodel.dto.user.RoleApplyDTO;
import com.stellarink.sharedmodel.dto.user.UserUpdateDTO;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.user.AuthorVO;
import com.stellarink.sharedmodel.vo.user.UserVO;
import com.stellarink.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    @GetMapping("/authors")
    public Response<List<AuthorVO>> listAuthors(@RequestParam("ids") List<Long> ids) {
        return Response.success(userService.listAuthors(ids));
    }

    @PutMapping("/profile")
    public Response<UserVO> updateProfile(@Valid @RequestBody UserUpdateDTO dto) {
        return Response.success(userService.updateProfile(AuthHelper.loginId(), dto));
    }

    /** 上传/替换自己的头像（multipart，字段名 file）；返回带 avatarUrl 的最新资料 */
    @PostMapping("/avatar")
    public Response<UserVO> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return Response.success(userService.uploadAvatar(AuthHelper.loginId(), file));
    }

    /** 删除自己的头像，回落为 avatarText 底字头像 */
    @DeleteMapping("/avatar")
    public Response<UserVO> deleteAvatar() {
        return Response.success(userService.deleteAvatar(AuthHelper.loginId()));
    }

    /** 修改密码：校验旧密码后更新（需登录） */
    @PutMapping("/password")
    public Response<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        userService.changePassword(AuthHelper.loginId(), dto);
        return Response.success();
    }

    /** 调整用户角色（仅站长，网关已做 ADMIN 门槛 + 服务内防御性校验）；
     *  通过/驳回作者申请都走这里：传 AUTHOR 为通过、传 READER 为驳回，都会清空待审申请 */
    @PutMapping("/{id}/role")
    public Response<UserVO> changeRole(@PathVariable Long id, @Valid @RequestBody ChangeRoleDTO dto) {
        return Response.success(userService.changeRole(AuthHelper.loginId(), id, dto.getRole()));
    }

    /** 读者申请成为作者（需登录，读者即可；网关对写请求默认要求登录） */
    @PutMapping("/role-apply")
    public Response<UserVO> applyRole(@Valid @RequestBody(required = false) RoleApplyDTO dto) {
        return Response.success(userService.applyRole(AuthHelper.loginId(), dto));
    }

    /** 撤回自己的作者申请（仅作者本人） */
    @PutMapping("/role-apply/cancel")
    public Response<UserVO> cancelRoleApply() {
        return Response.success(userService.cancelRoleApply(AuthHelper.loginId()));
    }
}
