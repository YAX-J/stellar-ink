package com.stellarink.content.post.pojo;

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
public class Post {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String content;

    /** 标签，逗号分隔 */
    private String tags;

    private Integer wordCount;

    /** 0 草稿 / 1 已发布 */
    private Integer status;

    /** 补充光芒数 */
    private Integer glow;

    /** 浏览量 */
    private Integer viewCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
