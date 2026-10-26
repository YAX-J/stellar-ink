package com.stellarink.sharedmodel.vo.series;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系列详情：系列本身 + 按顺序排好的篇目。
 *
 * <p>篇目可以是文章（{@code kind=post}）或笔记（{@code kind=note}）。
 * 前端据 kind 决定点击跳 {@code /read/{id}} 还是 {@code /note/{id}}。
 */
@Data
public class SeriesDetailVO {

    private Long id;

    private Long userId;

    private String title;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 公开篇目总数 */
    private Integer total;

    private List<ItemVO> items;

    @Data
    public static class ItemVO {
        /** post / note */
        private String kind;
        private Long id;
        private String title;
        /** yyyy-MM-dd */
        private String date;
        /** 系列内序号（人工可调，小到大） */
        private Integer order;
        private Integer wordCount;
    }
}
