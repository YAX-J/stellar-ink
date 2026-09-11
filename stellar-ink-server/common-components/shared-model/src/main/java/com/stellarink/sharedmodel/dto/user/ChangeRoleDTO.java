package com.stellarink.sharedmodel.dto.user;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理员调整用户角色入参。
 *
 * <p>角色合法性由服务层统一校验（{@code Role.parse}，大小写不敏感），
 * 不在 DTO 上用正则锁死，避免与枚举定义两处漂移。
 */
@Data
public class ChangeRoleDTO {

    /** 目标角色：READER / AUTHOR / ADMIN */
    @NotBlank(message = "角色不能为空。")
    private String role;
}
