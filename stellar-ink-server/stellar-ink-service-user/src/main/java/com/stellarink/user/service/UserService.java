package com.stellarink.user.service;

import com.stellarink.user.dto.LoginDTO;
import com.stellarink.user.dto.UserUpdateDTO;
import com.stellarink.user.vo.LoginVO;
import com.stellarink.user.vo.UserVO;

public interface UserService {

    LoginVO login(LoginDTO dto);

    UserVO profile(Long userId);

    UserVO updateProfile(Long userId, UserUpdateDTO dto);
}
