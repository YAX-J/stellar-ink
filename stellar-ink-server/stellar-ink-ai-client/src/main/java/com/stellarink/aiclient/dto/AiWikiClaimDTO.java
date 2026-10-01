package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一条带证据的原子主张（Python → Java）。
 *
 * <p>证据四件套 + 片段必须同时在：{@code postId}（哪篇文章）、{@code chunkIndex}（哪一段）、
 * {@code postVersion}（哪个版本）、{@code contentHash}（段落哈希）、{@code quote}（原文片段）。
 * 少了任何一件，这条主张就无法核验 —— 而「能核验」正是 Wiki 与「模型写一段摘要」的区别。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiClaimDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String text;

    private Long postId;

    private Integer chunkIndex;

    private String postVersion;

    private String contentHash;

    /** 原文片段：Python 侧已校验它**真的出现在该段落里** */
    private String quote;

    private String headingPath;

    private Double confidence;
}
