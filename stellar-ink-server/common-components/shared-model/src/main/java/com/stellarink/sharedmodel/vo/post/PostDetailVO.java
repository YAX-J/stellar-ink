package com.stellarink.sharedmodel.vo.post;

import lombok.Data;

import java.util.List;

/**
 * 深读舱文章详情，附带前后相邻星
 */
@Data
public class PostDetailVO {

    private Long id;

    private Long userId;

    private Integer status;

    private String title;

    private String content;

    private List<String> tags;

    private Integer wordCount;

    private Integer readMinutes;

    private Integer year;

    private String date;

    private Integer glow;

    /** 浏览量（登录用户按天去重统计） */
    private Integer viewCount;

    /** 当前登录用户是否已为这颗星补充过光芒（未登录恒为 false） */
    private Boolean liked;

    private NeighborVO prev;

    private NeighborVO next;

    @Data
    public static class NeighborVO {
        private Long id;
        private String title;
    }
}
