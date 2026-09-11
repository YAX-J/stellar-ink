package com.stellarink.sharedmodel.dto.user;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录入参。仅做长度上限（空值仍由服务层给出更友好的提示）。
 */
@Data
public class LoginDTO {

    @Size(max = 50, message = "用户名过长。")
    private String username;

    /** 上限用于阻断超长口令导致的 BCrypt 计算开销 */
    @Size(max = 100, message = "密码过长。")
    private String password;
}
