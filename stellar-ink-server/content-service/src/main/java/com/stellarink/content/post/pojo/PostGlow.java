package com.stellarink.content.post.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章点赞明细：唯一键 (post_id, user_id) 保证「一人一赞」，
 * post.glow 只是计数冗余，判断「我是否已赞」以本表为准。
 */
@Data
@TableName("post_glow")
public class PostGlow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;

    private Long userId;

    private LocalDateTime createdAt;
}
