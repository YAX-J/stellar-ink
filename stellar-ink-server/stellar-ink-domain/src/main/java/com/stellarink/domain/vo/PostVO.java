package com.stellarink.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 文章列表项（星尘卡片 / 星图 / 长卷共用）
 */
@Data
public class PostVO {

    private Long id;

    private String title;

    /** 摘要：正文前 60 字 */
    private String summary;

    private List<String> tags;

    private Integer wordCount;

    private Integer year;

    /** yyyy-MM-dd */
    private String date;

    private Integer glow;

    private Integer status;
}
