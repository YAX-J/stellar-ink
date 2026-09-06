package com.stellarink.user.dto;

import lombok.Data;

/**
 * 身份舱 / 星籍资料更新
 */
@Data
public class UserUpdateDTO {

    private String nickname;

    private String signature;

    private String avatarText;

    private Integer dailyGoal;
}
