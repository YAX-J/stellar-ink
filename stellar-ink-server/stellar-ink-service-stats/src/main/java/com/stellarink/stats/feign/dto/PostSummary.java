package com.stellarink.stats.feign.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * post-service /internal/posts/summary 的响应模型（服务间契约，与提供方字段对齐）
 */
@Data
public class PostSummary {

    private Long id;

    private Integer wordCount;

    /** 标签，逗号分隔 */
    private String tags;

    private LocalDateTime createdAt;
}
