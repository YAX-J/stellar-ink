package com.stellarink.service.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.common.exception.BusinessException;
import com.stellarink.common.result.ResultCode;
import com.stellarink.common.util.JwtUtil;
import com.stellarink.dao.entity.UserEntity;
import com.stellarink.dao.mapper.UserMapper;
import com.stellarink.domain.dto.LoginDTO;
import com.stellarink.domain.dto.UserUpdateDTO;
import com.stellarink.domain.vo.LoginVO;
import com.stellarink.domain.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public LoginVO login(LoginDTO dto) {
        if (!StringUtils.hasText(dto.getUsername()) || !StringUtils.hasText(dto.getPassword())) {
            throw new BusinessException("用户名和密码都要填。");
        }
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, dto.getUsername().trim()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            log.warn("登录失败 username={} 原因={}", dto.getUsername(),
                    user == null ? "用户不存在" : "密码不匹配");
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码不对。");
        }
        log.info("登录成功 userId={} username={}", user.getId(), user.getUsername());
        return new LoginVO(jwtUtil.create(user.getId(), user.getUsername()), toVO(user));
    }

    @Override
    public UserVO profile(Long userId) {
        return toVO(requireUser(userId));
    }

    @Override
    public UserVO updateProfile(Long userId, UserUpdateDTO dto) {
        UserEntity user = requireUser(userId);
        if (StringUtils.hasText(dto.getNickname())) {
            user.setNickname(dto.getNickname().trim());
        }
        if (dto.getSignature() != null) {
            user.setSignature(dto.getSignature().trim());
        }
        if (StringUtils.hasText(dto.getAvatarText())) {
            user.setAvatarText(dto.getAvatarText().trim());
        }
        if (dto.getDailyGoal() != null) {
            user.setDailyGoal(Math.max(0, dto.getDailyGoal()));
        }
        userMapper.updateById(user);
        log.info("更新资料 userId={} nickname={} dailyGoal={}", userId, user.getNickname(), user.getDailyGoal());
        return toVO(user);
    }

    private UserEntity requireUser(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "站长不在舰桥上");
        }
        return user;
    }

    private UserVO toVO(UserEntity user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setSignature(user.getSignature());
        vo.setAvatarText(user.getAvatarText());
        vo.setDailyGoal(user.getDailyGoal());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
