package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 实体之间的**共现**关系（E4-5，Python → Java）。
 *
 * <p>语义：同一句主张里同时出现的两个实体连一条边，`weight` 是「被一起谈论的主张条数」。
 *
 * <p>⚠️ 它**不是**语义关系（因果 / 属于 / 依赖）：那些需要模型抽取 + 人工审核。
 * 如实叫「共现」，别在界面上说成「知识图谱里的因果关系」——
 * 那是把一个可核对的事实说成一个需要论证的判断。
 *
 * <p>无向边只有一种表示（{@code source < target}，两端都是规范化名字），
 * 否则 (A,B) 与 (B,A) 会各存一行、权重看起来只有实际的一半。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiRelationDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String source;

    private String target;

    private Integer weight;

    /** 这条边是从哪几句主张里看出来的 —— 边也要能回到原文 */
    private List<EvidenceDTO> evidence;

    /** 一条边的证据：哪篇文章的哪句话同时提到了这两个实体。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceDTO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long postId;

        private Integer chunkIndex;

        private String claimText;
    }
}
