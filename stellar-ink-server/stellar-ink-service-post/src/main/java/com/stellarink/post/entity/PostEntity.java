package com.stellarink.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章（星）
 */
@Data
@TableName("post")
public class PostEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String content;

    /** 标签，逗号分隔 */
    private String tags;

    private Integer wordCount;

    /** 0 草稿 / 1 已发布 */
    private Integer status;

    /** 补充光芒数 */
    private Integer glow;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
