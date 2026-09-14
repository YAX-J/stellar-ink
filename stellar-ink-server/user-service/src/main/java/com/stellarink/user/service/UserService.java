package com.stellarink.user.service;

import com.stellarink.sharedmodel.dto.user.ChangePasswordDTO;
import com.stellarink.sharedmodel.dto.user.LoginDTO;
import com.stellarink.sharedmodel.dto.user.RegisterDTO;
import com.stellarink.sharedmodel.dto.user.RoleApplyDTO;
import com.stellarink.sharedmodel.dto.user.UserUpdateDTO;
import com.stellarink.sharedmodel.vo.user.AuthorVO;
import com.stellarink.sharedmodel.vo.user.UserVO;

import java.util.List;

public interface UserService {

    /** 登录并签发 Sa-Token JWT */
    UserVO login(LoginDTO dto);

    /** 注册新账号（开放注册）并签发 Sa-Token JWT */
    UserVO register(RegisterDTO dto);

    /** 修改当前用户密码（校验旧密码） */
    void changePassword(Long userId, ChangePasswordDTO dto);

    /** 管理员调整用户角色（仅 ADMIN，服务内再做防御性校验）；同时清空待审申请 */
    UserVO changeRole(Long operatorId, Long targetUserId, String role);

    /** 读者申请成为作者（需登录，读者即可）；已提交过则覆盖为最新理由与时间 */
    UserVO applyRole(Long userId, RoleApplyDTO dto);

    /** 撤回自己的申请（仅作者本人） */
    UserVO cancelRoleApply(Long userId);

    /** 管理员列出全部用户（仅 ADMIN，供角色管理页枚举） */
    List<UserVO> listUsers(Long operatorId);

    /** 批量查询公开作者摘要，供文章和流星署名。 */
    List<AuthorVO> listAuthors(List<Long> ids);

    UserVO profile(Long userId);

    UserVO updateProfile(Long userId, UserUpdateDTO dto);
}
