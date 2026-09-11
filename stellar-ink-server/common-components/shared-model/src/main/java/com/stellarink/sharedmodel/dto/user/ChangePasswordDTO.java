package com.stellarink.sharedmodel.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码入参。
 */
@Data
public class ChangePasswordDTO {

    /** 原密码 */
    @NotBlank(message = "原密码不能为空。")
    @Size(max = 64, message = "原密码最长 64 位。")
    private String oldPassword;

    /** 新密码：6~64 位（BCrypt 仅取前 72 字节，64 位上限留足安全余量） */
    @NotBlank(message = "新密码不能为空。")
    @Size(min = 6, max = 64, message = "新密码需 6~64 位。")
    private String newPassword;
}
