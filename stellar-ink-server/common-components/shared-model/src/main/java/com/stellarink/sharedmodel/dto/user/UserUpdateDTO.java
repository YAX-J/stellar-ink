package com.stellarink.sharedmodel.dto.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 站长资料更新入参。长度上限对齐 user 表列宽，避免超长值直插数据库。
 */
@Data
public class UserUpdateDTO {

    @Size(max = 50, message = "笔名最多 50 个字。")
    private String nickname;

    @Size(max = 200, message = "星图签名最多 200 个字。")
    private String signature;

    @Size(max = 10, message = "头像底字最多 10 个字符。")
    private String avatarText;

    @Min(value = 0, message = "每日目标不能是负数。")
    @Max(value = 1000000, message = "每日目标太大啦。")
    private Integer dailyGoal;
}
