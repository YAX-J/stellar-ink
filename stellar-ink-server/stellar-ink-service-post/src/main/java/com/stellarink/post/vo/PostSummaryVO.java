package com.stellarink.post.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务间内部接口：给 stats-service 的轻量文章汇总
 */
@Data
public class PostSummaryVO {

    private Long id;

    private Integer wordCount;

    /** 标签，逗号分隔 */
    private String tags;

    private LocalDateTime createdAt;
}
