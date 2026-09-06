package com.stellarink.service.user;

import com.stellarink.domain.dto.LoginDTO;
import com.stellarink.domain.dto.UserUpdateDTO;
import com.stellarink.domain.vo.LoginVO;
import com.stellarink.domain.vo.UserVO;

public interface UserService {

    LoginVO login(LoginDTO dto);

    UserVO profile(Long userId);

    UserVO updateProfile(Long userId, UserUpdateDTO dto);
}
