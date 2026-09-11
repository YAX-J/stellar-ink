package com.stellarink.sharedmodel.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册入参。
 *
 * <p>用户名与密码必填；笔名选填，缺省用登录名。用户名唯一性由
 * {@code user} 表的 {@code uk_username}（utf8mb4_unicode_ci，大小写不敏感）兜底。
 */
@Data
public class RegisterDTO {

    /** 登录名：3~50 位，仅字母/数字/下划线/连字符 */
    @NotBlank(message = "登录名不能为空。")
    @Size(min = 3, max = 50, message = "登录名需 3~50 个字符。")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "登录名只能包含字母、数字、下划线或连字符。")
    private String username;

    /** 登录密码：6~64 位（BCrypt 仅取前 72 字节，64 位上限留足安全余量） */
    @NotBlank(message = "密码不能为空。")
    @Size(min = 6, max = 64, message = "密码需 6~64 位。")
    private String password;

    /** 笔名：选填，最长 50 字 */
    @Size(max = 50, message = "笔名最多 50 个字。")
    private String nickname;
}
