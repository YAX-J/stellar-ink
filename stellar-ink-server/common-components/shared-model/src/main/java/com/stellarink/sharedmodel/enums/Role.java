package com.stellarink.sharedmodel.enums;

import lombok.Getter;

import java.util.Locale;

/**
 * 用户角色（权限门槛，非内容隔离）。
 *
 * <p>三级递进，权限向下累积：{@link #ADMIN 站长} ⊃ {@link #AUTHOR 作者} ⊃ {@link #READER 读者}。
 * 角色只作为「能不能做某类操作」的开关，不参与数据归属判定（文章/流星不区分作者，见 AGENTS.md）。
 *
 * <p>角色值在登录/注册时写入 Sa-Token JWT 的 extra 字段（键 {@link #JWT_KEY}），
 * 网关与下游服务读取同一字段做门槛校验。
 */
@Getter
public enum Role {

    /** 读者：仅可读与公开互动（点赞/投瓶/申请友链），不可创作 */
    READER(1, "读者"),

    /** 作者：可创作与维护文章/流星 */
    AUTHOR(2, "作者"),

    /** 站长：拥有作者全部能力 + 友链审核 + 用户角色管理 */
    ADMIN(3, "站长");

    /** JWT extra 键名：登录时写入角色，网关/服务读取 */
    public static final String JWT_KEY = "role";

    /** 权限等级，越大权限越高 */
    private final int level;

    /** 中文名 */
    private final String label;

    Role(int level, String label) {
        this.level = level;
        this.label = label;
    }

    /** 是否至少达到指定角色（ADMIN ⊃ AUTHOR ⊃ READER） */
    public boolean atLeast(Role required) {
        return this.level >= required.level;
    }

    /**
     * 严格解析角色名（大小写不敏感）。
     *
     * @return 为空或无法识别时返回 {@code null}
     */
    public static Role parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 宽容解析：无法识别时回退为 {@link #READER}（用于读取 JWT 中的角色，缺省即最低权限）。
     */
    public static Role parseOrDefault(String value) {
        Role role = parse(value);
        return role == null ? READER : role;
    }
}
