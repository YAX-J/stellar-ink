package com.stellarink.sharedmodel.vo.user;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserVO {

    private Long id;

    private String username;

    /** 笔名 */
    private String nickname;

    /** 星图签名 */
    private String signature;

    /** 头像底字（如「星」） */
    private String avatarText;

    /** 每日星尘目标（字） */
    private Integer dailyGoal;

    /** 角色：READER 读者 / AUTHOR 作者 / ADMIN 站长 */
    private String role;

    /** 申请成为作者的时间；非空表示有一条待审核申请（站长据此得到审核队列） */
    private LocalDateTime roleAppliedAt;

    /** 申请理由，供站长审核参考 */
    private String roleApplyNote;

    private LocalDateTime createdAt;
}
