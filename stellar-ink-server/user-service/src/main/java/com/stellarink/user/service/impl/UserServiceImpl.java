package com.stellarink.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stellarink.sharedmodel.dto.user.LoginDTO;
import com.stellarink.sharedmodel.dto.user.UserUpdateDTO;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.user.UserVO;
import com.stellarink.user.mapper.UserMapper;
import com.stellarink.user.pojo.User;
import com.stellarink.user.service.UserService;
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
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public UserVO login(LoginDTO dto) {
        if (!StringUtils.hasText(dto.getUsername()) || !StringUtils.hasText(dto.getPassword())) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "用户名和密码都要填。");
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.getUsername().trim()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            log.warn("登录失败 username={} 原因={}", dto.getUsername(),
                    user == null ? "用户不存在" : "密码不匹配");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码不对。");
        }
        // Sa-Token JWT 无状态登录：token 由网关与各服务用相同密钥验签
        StpUtil.login(user.getId());
        log.info("登录成功 userId={} username={}", user.getId(), user.getUsername());
        return toVO(user);
    }

    @Override
    public UserVO profile(Long userId) {
        return toVO(requireUser(userId));
    }

    @Override
    public UserVO updateProfile(Long userId, UserUpdateDTO dto) {
        User user = requireUser(userId);
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

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "站长不在舰桥上");
        }
        return user;
    }

    private UserVO toVO(User user) {
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
