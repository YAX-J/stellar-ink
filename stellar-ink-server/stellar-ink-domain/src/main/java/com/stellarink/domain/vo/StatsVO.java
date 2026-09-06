package com.stellarink.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 写作脉搏总览
 */
@Data
public class StatsVO {

    /** 已点亮文章数 */
    private Long totalPosts;

    /** 累计星尘（总字数） */
    private Long totalWords;

    /** 今晚已写字数 */
    private Long todayWords;

    /** 连续写作夜数 */
    private Integer streakDays;

    /** 写于深夜（22 点后）的比例，0-100 */
    private Integer nightRatio;

    /** 标签分布 */
    private List<TagVO> tagDistribution;
}
