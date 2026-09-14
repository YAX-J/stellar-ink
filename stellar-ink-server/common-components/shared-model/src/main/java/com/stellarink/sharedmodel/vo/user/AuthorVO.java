package com.stellarink.sharedmodel.vo.user;

import lombok.Data;

/**
 * 面向公开内容展示的作者摘要，不暴露登录名等账号信息。
 */
@Data
public class AuthorVO {

    private Long id;

    private String nickname;

    private String avatarText;

    /** 头像图片相对路径；为空时前端用 avatarText 渲染文字头像 */
    private String avatarUrl;
}
