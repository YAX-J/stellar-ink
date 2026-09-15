package com.stellarink.sharedmodel.vo.comment;

import lombok.Data;

import java.time.LocalDateTime;

/** 文章评论展示对象。 */
@Data
public class CommentVO {

    private Long id;

    private Long postId;

    private Long userId;

    private String content;

    private LocalDateTime createdAt;
}
