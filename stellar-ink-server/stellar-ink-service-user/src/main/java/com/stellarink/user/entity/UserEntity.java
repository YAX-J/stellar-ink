package com.stellarink.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站长用户（单用户博客；身份舱/星籍资料合并于此）
 */
@Data
@TableName("user")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 哈希 */
    private String password;

    /** 笔名 */
    private String nickname;

    /** 星图签名 */
    private String signature;

    /** 头像底字 */
    private String avatarText;

    /** 每日星尘目标（字） */
    private Integer dailyGoal;

    private LocalDateTime createdAt;
}
