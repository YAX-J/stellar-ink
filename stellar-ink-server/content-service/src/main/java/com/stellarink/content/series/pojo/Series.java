package com.stellarink.content.series.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系列 / 专栏。
 *
 * <p>一个系列可以同时收文章与笔记（技术博客常把「教程长文」和「踩坑短记」放在同一条线上），
 * 篇目顺序由内容表自己的 {@code series_order} 决定，这个类只管系列本身的标题与简介。
 */
@Data
@TableName("series")
public class Series {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
