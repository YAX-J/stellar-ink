package com.stellarink.sharedmodel.vo.series;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系列列表项。
 *
 * <p>计数只统计公开内容（已发布文章 + 公开已发布笔记）——
 * 读者看到的「共 N 篇」必须与他点进去能读到的篇数一致。
 */
@Data
public class SeriesVO {

    private Long id;

    private Long userId;

    private String title;

    private String description;

    /** 文章篇数 */
    private Integer postCount;

    /** 公开笔记条数 */
    private Integer noteCount;

    /** 合计（postCount + noteCount） */
    private Integer itemCount;

    private LocalDateTime updatedAt;
}
