package com.stellarink.sharedmodel.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一条 Wiki 主张（读者侧视图，E4-2）。
 *
 * <p>**证据是这份视图的主体，不是附注**：前端渲染时 `quote` 要与主张一起显示，
 * 并给出回到原文的入口（`postId` + `chunkIndex`）。只显示主张的话，
 * 读者无法判断这是文章说的还是模型编的 —— 那正是 Wiki 最该避免的形态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiClaimVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long postId;

    private Integer chunkIndex;

    private String postVersion;

    private String contentHash;

    private String text;

    /** 原文片段：与主张一起展示，供人肉眼核对 */
    private String quote;

    private String headingPath;

    private Double confidence;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
