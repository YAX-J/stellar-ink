package com.stellarink.user.service;

import com.stellarink.sharedmodel.dto.user.LoginDTO;
import com.stellarink.sharedmodel.dto.user.UserUpdateDTO;
import com.stellarink.sharedmodel.vo.user.UserVO;

public interface UserService {

    /** 登录并签发 Sa-Token JWT */
    UserVO login(LoginDTO dto);

    UserVO profile(Long userId);

    UserVO updateProfile(Long userId, UserUpdateDTO dto);
}
