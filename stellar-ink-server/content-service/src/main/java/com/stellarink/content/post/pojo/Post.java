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

    /** 所属系列（NULL = 不属任何系列） */
    private Long seriesId;

    /** 系列内序号（小到大）；NULL 表示没排过，阅读页排在最后 */
    private Integer seriesOrder;

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
