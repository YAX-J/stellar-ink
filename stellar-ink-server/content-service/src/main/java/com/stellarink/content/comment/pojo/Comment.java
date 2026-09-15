package com.stellarink.content.comment.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 文章评论。 */
@Data
@TableName("post_comment")
public class Comment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;

    private Long userId;

    private String content;

    /** 1 正常 / 0 已删除 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
