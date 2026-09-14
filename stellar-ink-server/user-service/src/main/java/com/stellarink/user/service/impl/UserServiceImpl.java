package com.stellarink.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.dto.user.ChangePasswordDTO;
import com.stellarink.sharedmodel.dto.user.LoginDTO;
import com.stellarink.sharedmodel.dto.user.RegisterDTO;
import com.stellarink.sharedmodel.dto.user.RoleApplyDTO;
import com.stellarink.sharedmodel.dto.user.UserUpdateDTO;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.user.AuthorVO;
import com.stellarink.sharedmodel.vo.user.UserVO;
import com.stellarink.user.mapper.UserMapper;
import com.stellarink.user.component.AvatarStorage;
import com.stellarink.user.pojo.User;
import com.stellarink.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final AvatarStorage avatarStorage;

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
        // Sa-Token JWT 无状态登录：token 由网关与各服务用相同密钥验签；角色一并写入 JWT 供门槛校验
        StpUtil.login(user.getId(), SaLoginParameter.create().setExtra(Role.JWT_KEY, roleOf(user)));
        log.info("登录成功 userId={} username={}", user.getId(), user.getUsername());
        return toVO(user);
    }

    @Override
    public UserVO register(RegisterDTO dto) {
        String username = dto.getUsername().trim();
        // 唯一性预检：uk_username + utf8mb4_unicode_ci（大小写不敏感）
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "这个登录名已经被占用了，换一个吧。");
        }
        LocalDateTime now = LocalDateTime.now();
        User entity = new User();
        entity.setUsername(username);
        entity.setPassword(passwordEncoder.encode(dto.getPassword()));
        entity.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname().trim() : username);
        entity.setDailyGoal(500);
        // 开放注册一律 READER，不信任客户端自报角色
        entity.setRole(Role.READER.name());
        entity.setCreatedAt(now);
        try {
            userMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发兜底：唯一键冲突统一转成友好提示
            throw new BusinessException(ErrorCode.PARAM_ERROR, "这个登录名已经被占用了，换一个吧。");
        }
        log.info("注册新账号 userId={} username={}", entity.getId(), entity.getUsername());
        // 注册即登录：与 login 一致签发 JWT（新账号固定 READER）
        StpUtil.login(entity.getId(), SaLoginParameter.create().setExtra(Role.JWT_KEY, Role.READER.name()));
        return toVO(entity);
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
    public UserVO changeRole(Long operatorId, Long targetUserId, String role) {
        // 防御性校验：即便网关漏拦，也拒绝非 ADMIN 操作（读 JWT 中的角色）
        AuthHelper.requireAtLeast(Role.ADMIN);
        Role target = Role.parse(role);
        if (target == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "角色不合法，可选 READER / AUTHOR / ADMIN。");
        }
        if (operatorId != null && operatorId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能修改自己的角色，以免把自己锁在门外。");
        }
        User user = requireUser(targetUserId);
        user.setRole(target.name());
        /* 同一处收口：只要角色被主动调整过（通过或驳回），待审申请就应当消失。
         * 这样「点通过」与「直接在成员列表里改角色」两条路径不会留下自相矛盾的待审状态。 */
        user.setRoleAppliedAt(null);
        user.setRoleApplyNote(null);
        userMapper.updateById(user);
        log.info("调整角色 operatorId={} targetUserId={} role={}（同时清空待审申请）",
                operatorId, targetUserId, target.name());
        return toVO(user);
    }

    @Override
    public UserVO applyRole(Long userId, RoleApplyDTO dto) {
        User user = requireUser(userId);
        Role current = Role.parseOrDefault(user.getRole());
        if (current.atLeast(Role.AUTHOR)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "你已经是" + current.getLabel() + "，不需要再申请。");
        }
        /* 允许覆盖式重新提交：重复申请不报错，顺手把理由和时间更新为最新 */
        String note = dto == null ? null : dto.getNote();
        user.setRoleAppliedAt(LocalDateTime.now());
        user.setRoleApplyNote(StringUtils.hasText(note) ? note.trim() : null);
        userMapper.updateById(user);
        log.info("收到作者申请 userId={} note长度={}", userId,
                user.getRoleApplyNote() == null ? 0 : user.getRoleApplyNote().length());
        return toVO(user);
    }

    @Override
    public UserVO cancelRoleApply(Long userId) {
        User user = requireUser(userId);
        if (user.getRoleAppliedAt() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "当前没有待审核的申请。");
        }
        user.setRoleAppliedAt(null);
        user.setRoleApplyNote(null);
        userMapper.updateById(user);
        log.info("撤回作者申请 userId={}", userId);
        return toVO(user);
    }

    @Override
    public List<UserVO> listUsers(Long operatorId) {
        // 防御性校验：即便网关漏拦，也拒绝非 ADMIN 操作
        AuthHelper.requireAtLeast(Role.ADMIN);
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                        .orderByAsc(User::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public List<AuthorVO> listAuthors(List<Long> ids) {
        List<Long> safeIds = ids == null ? List.of() : ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (safeIds.size() > 100) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "一次最多查询 100 位作者。");
        }
        if (safeIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(safeIds).stream().map(this::toAuthorVO).toList();
    }

    @Override
    public void changePassword(Long userId, ChangePasswordDTO dto) {
        User user = requireUser(userId);
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "原密码不正确。");
        }
        if (passwordEncoder.matches(dto.getNewPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "新密码不能与旧密码相同。");
        }
        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        userMapper.updateById(user);
        log.info("修改密码成功 userId={}", userId);
        // 注意：JWT 无状态，改密后旧 token 仍有效（项目既有取舍，无法服务端吊销）
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

    @Override
    public UserVO uploadAvatar(Long userId, MultipartFile file) {
        User user = requireUser(userId);
        String oldUrl = user.getAvatarUrl();
        String newUrl = avatarStorage.store(userId, file);
        user.setAvatarUrl(newUrl);
        try {
            userMapper.updateById(user);
        } catch (RuntimeException e) {
            /* 写库失败就把刚落盘的新文件删掉，避免留下一个谁也引用不到的孤儿文件 */
            avatarStorage.delete(newUrl);
            throw e;
        }
        // 换头像成功后再删旧文件：先删后写会在一失败时既丢新图又丢旧图
        avatarStorage.delete(oldUrl);
        log.info("更新头像 userId={} url={}", userId, newUrl);
        return toVO(user);
    }

    @Override
    public UserVO deleteAvatar(Long userId) {
        User user = requireUser(userId);
        String oldUrl = user.getAvatarUrl();
        if (!StringUtils.hasText(oldUrl)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "当前没有上传过头像。");
        }
        /* 必须用 UpdateWrapper 显式 set null：MyBatis-Plus 的 updateById 默认忽略 null 字段
         * （FieldStrategy.NOT_NULL），直接 setAvatarUrl(null) 会生成一条不含 avatar_url 的 UPDATE，
         * 接口返回「成功」但库里旧路径仍在 —— 刷新页面头像又回来了。 */
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getAvatarUrl, null));
        user.setAvatarUrl(null);
        avatarStorage.delete(oldUrl);
        log.info("删除头像 userId={} 已回落为底字头像", userId);
        return toVO(user);
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "站长不在舰桥上");
        }
        return user;
    }

    /** 规范化用户角色：DB 值缺失或非法时回退 READER */
    private String roleOf(User user) {
        return Role.parseOrDefault(user.getRole()).name();
    }

    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setSignature(user.getSignature());
        vo.setAvatarText(user.getAvatarText());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setDailyGoal(user.getDailyGoal());
        vo.setRole(roleOf(user));
        vo.setRoleAppliedAt(user.getRoleAppliedAt());
        vo.setRoleApplyNote(user.getRoleApplyNote());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }

    private AuthorVO toAuthorVO(User user) {
        AuthorVO vo = new AuthorVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatarText(user.getAvatarText());
        vo.setAvatarUrl(user.getAvatarUrl());
        return vo;
    }
}
