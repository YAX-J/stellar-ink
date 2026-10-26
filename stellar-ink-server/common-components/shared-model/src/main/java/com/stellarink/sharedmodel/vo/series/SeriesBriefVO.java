package com.stellarink.sharedmodel.vo.series;

import lombok.Data;

/**
 * 阅读页的系列徽章：「本系列 · 第 index 篇 / 共 total 篇」。
 *
 * <p>不属于任何系列时整个字段为 {@code null}（而不是 index=0）——
 * 前端据此决定整块不出现，避免出现「第 0 篇」这种假信息。
 */
@Data
public class SeriesBriefVO {

    private Long id;

    private String title;

    /** 当前这篇在系列里的位置，从 1 开始 */
    private Integer index;

    /** 系列公开篇目总数 */
    private Integer total;
}
