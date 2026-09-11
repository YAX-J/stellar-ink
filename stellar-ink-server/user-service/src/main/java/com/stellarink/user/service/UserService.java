package com.stellarink.user.service;

import com.stellarink.sharedmodel.dto.user.ChangePasswordDTO;
import com.stellarink.sharedmodel.dto.user.LoginDTO;
import com.stellarink.sharedmodel.dto.user.RegisterDTO;
import com.stellarink.sharedmodel.dto.user.UserUpdateDTO;
import com.stellarink.sharedmodel.vo.user.UserVO;

import java.util.List;

public interface UserService {

    /** 登录并签发 Sa-Token JWT */
    UserVO login(LoginDTO dto);

    /** 注册新账号（开放注册）并签发 Sa-Token JWT */
    UserVO register(RegisterDTO dto);

    /** 修改当前用户密码（校验旧密码） */
    void changePassword(Long userId, ChangePasswordDTO dto);

    /** 管理员调整用户角色（仅 ADMIN，服务内再做防御性校验） */
    UserVO changeRole(Long operatorId, Long targetUserId, String role);

    /** 管理员列出全部用户（仅 ADMIN，供角色管理页枚举） */
    List<UserVO> listUsers(Long operatorId);

    UserVO profile(Long userId);

    UserVO updateProfile(Long userId, UserUpdateDTO dto);
}
