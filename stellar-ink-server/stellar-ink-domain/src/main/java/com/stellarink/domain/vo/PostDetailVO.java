package com.stellarink.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 深读舱文章详情，附带前后相邻星
 */
@Data
public class PostDetailVO {

    private Long id;

    private String title;

    private String content;

    private List<String> tags;

    private Integer wordCount;

    private Integer readMinutes;

    private Integer year;

    private String date;

    private Integer glow;

    private NeighborVO prev;

    private NeighborVO next;

    @Data
    public static class NeighborVO {
        private Long id;
        private String title;
    }
}
