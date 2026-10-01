package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条带证据的 Wiki 主张（表 {@code ai_wiki_claim}，见 {@code deploy/sql/13_ai_wiki.sql}）。
 *
 * <p>证据四件套（{@code postId} / {@code chunkIndex} / {@code postVersion} / {@code contentHash}）
 * 加原文片段 {@code quote} 必须同时在：缺任何一件，这条主张就无法核验，
 * 而「能核验」正是 Wiki 与「模型写一段摘要」的区别。
 *
 * <p><b>幂等锚点是 (postId, contentHash, claimText)</b>：同一段落的同一版本重复抽取不会产生
 * 重复行 —— 重复构建是常态（文章新增、模型换版本都会触发重跑），
 * 没有幂等锚点的话库会一天天膨胀，而且看起来「一直在产出新知识」。
 */
@Data
@TableName("ai_wiki_claim")
public class AiWikiClaim {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;

    private Integer chunkIndex;

    /** 文章内容版本：文章改了，这条主张就该重算 */
    private String postVersion;

    /** 段落内容哈希：用于只失效受影响的那几条 */
    private String contentHash;

    private String claimText;

    /** 原文片段：抽取时已校验它真的出现在该段落里 */
    private String quote;

    private String headingPath;

    private BigDecimal confidence;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
