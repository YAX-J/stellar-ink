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

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** 连续失败次数达到该值即锁定 */
    private static final int MAX_FAILURES = 5;
    /** 失败计数窗口 */
    private static final long FAILURE_WINDOW_MS = 15 * 60 * 1000L;
    /** 锁定时长 */
    private static final long LOCK_MS = 15 * 60 * 1000L;

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * 登录失败计数器（进程内，单实例部署足够）。
     * <p>按<b>用户名</b>计数而非 IP：请求可能经 Nginx/网关转发，来源 IP 可被 X-Forwarded-For 伪造，
     * 按 IP 限流会形同虚设。按账号锁定至少能保护站长账号不被爆破。
     * <p>注意：多实例部署时需换成 Redis 共享计数。
     */
    private final Map<String, Attempt> loginAttempts = new ConcurrentHashMap<>();

    @Override
    public UserVO login(LoginDTO dto) {
        if (!StringUtils.hasText(dto.getUsername()) || !StringUtils.hasText(dto.getPassword())) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "用户名和密码都要填。");
        }
        String attemptKey = dto.getUsername().trim().toLowerCase(Locale.ROOT);
        requireNotLocked(attemptKey);

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.getUsername().trim()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            long lockedSeconds = recordFailure(attemptKey);
            log.warn("登录失败 username={} 原因={} 剩余尝试次数={}", dto.getUsername(),
                    user == null ? "用户不存在" : "密码不匹配",
                    lockedSeconds > 0 ? 0 : Math.max(0, remainingAttempts(attemptKey)));
            if (lockedSeconds > 0) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "尝试次数过多，账号已锁定 " + (lockedSeconds / 60) + " 分钟，请稍后再试。");
            }
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码不对。");
        }
        clearFailures(attemptKey);
        // Sa-Token JWT 无状态登录：token 由网关与各服务用相同密钥验签
        StpUtil.login(user.getId());
        log.info("登录成功 userId={} username={}", user.getId(), user.getUsername());
        return toVO(user);
    }

    /** 已锁定则直接拒绝，避免继续付出 BCrypt 计算开销 */
    private void requireNotLocked(String key) {
        Attempt attempt = loginAttempts.get(key);
        if (attempt == null) {
            return;
        }
        synchronized (attempt) {
            long now = System.currentTimeMillis();
            if (attempt.lockedUntil > now) {
                long remainSeconds = (attempt.lockedUntil - now) / 1000;
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "尝试次数过多，账号已锁定 " + Math.max(1, remainSeconds / 60) + " 分钟，请稍后再试。");
            }
        }
    }

    /** @return 本次触发的锁定剩余秒数；未触发锁定返回 0 */
    private long recordFailure(String key) {
        Attempt attempt = loginAttempts.computeIfAbsent(key, k -> new Attempt());
        synchronized (attempt) {
            long now = System.currentTimeMillis();
            if (now - attempt.windowStart > FAILURE_WINDOW_MS) {
                attempt.count = 0;
                attempt.windowStart = now;
            }
            attempt.count++;
            if (attempt.count >= MAX_FAILURES) {
                attempt.lockedUntil = now + LOCK_MS;
                attempt.count = 0;
                attempt.windowStart = now;
                return LOCK_MS / 1000;
            }
            return 0;
        }
    }

    private int remainingAttempts(String key) {
        Attempt attempt = loginAttempts.get(key);
        return attempt == null ? MAX_FAILURES : Math.max(0, MAX_FAILURES - attempt.count);
    }

    private void clearFailures(String key) {
        loginAttempts.remove(key);
    }

    /** 可变计数单元，内部加锁访问 */
    private static final class Attempt {
        private int count;
        private long windowStart = System.currentTimeMillis();
        private long lockedUntil;
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
