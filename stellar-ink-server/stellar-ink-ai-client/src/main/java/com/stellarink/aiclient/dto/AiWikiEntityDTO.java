package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 合并后的实体（E4-4，Python → Java）。
 *
 * <p>合并只做**确定性归一化**（全角/半角、大小写、空白、首尾标点），不做语义合并：
 * 「星笺」与「STELLAR INK」是同一个东西，但错合一组的代价是一个说不清的知识条目 ——
 * roadmap §14 第 2 步要求的 ADMIN 审核流还没做，所以宁可不合（已记录为后续切片）。
 *
 * <p>每个 {@code mention} 都带着它**挂在哪条主张上**：那是实体回到证据的那条线。
 * 没有它，图上的节点就无从核对。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiEntityDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 代表写法（出现最多的那种） */
    private String name;

    /** 归一化后的键（合并的依据） */
    private String normalized;

    /** person / concept / tool / org / place / other */
    private String kind;

    private Integer count;

    /** 出自哪几篇文章 */
    private List<Long> postIds;

    private List<AiWikiEntityMentionDTO> mentions;

    /** 一次具体出现。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiWikiEntityMentionDTO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String name;

        private Long postId;

        private Integer chunkIndex;

        /** 它出现在这条主张（或它的原文片段）里 */
        private String claimText;
    }
}
