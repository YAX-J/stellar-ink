package com.stellarink.sharedmodel.dto.user;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 读者申请成为作者的入参。
 *
 * <p>理由只作为站长审核的上下文，不做强制性校验；写不下也可以留空提交。
 * 申请频率不做服务端限流 —— 这是私人站点，站长自己看得见申请人是谁。
 */
@Data
public class RoleApplyDTO {

    /** 申请理由（想写什么、为什么），最多 200 字 */
    @Size(max = 200, message = "申请理由最多 200 个字。")
    private String note;
}
