package com.stellarink.sharedmodel.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 一个实体在**某篇文章**里的样子（读者侧视图，E4-7）。
 *
 * <p>三块字段刻意放在一起，因为实体页要能自己站得住：
 * <ul>
 *   <li>{@code mentionCount} / {@code postCount} 是**全站**计数（这个话题被谈了多少次）；</li>
 *   <li>{@code mentions} 是**本文**里它出现的那几句主张 —— 光有计数等于让人相信一个数字；</li>
 *   <li>{@code relations} 是它与谁被一起谈论（**共现**，不是语义关系），按权重降序。</li>
 * </ul>
 * ⚠️ 别把「本文提及数」当成「全局提及数」显示：两个数字含义不同，
 * 混起来会让人以为这篇文章被引用了很多次。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiEntityVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 代表写法（出现最多的那种） */
    private String name;

    private String normalized;

    /** person/concept/tool/org/place/other */
    private String kind;

    /** 全站：它出现在几条主张里 */
    private Integer mentionCount;

    /** 全站：它涉及几篇文章 */
    private Integer postCount;

    /** **本文**中的提及（读者在这里核对） */
    private List<MentionVO> mentions;

    /** 与谁被一起谈论（共现），权重降序 */
    private List<RelationVO> relations;

    /** 一次提及：回到某篇文章的某句主张。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MentionVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long postId;

        private Integer chunkIndex;

        private String claimText;
    }

    /** 一条共现关系：另一端实体 + 权重 + 证据（哪几句主张同时提到两者）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long entityId;

        /** 另一端实体的名字（读者要看的） */
        private String name;

        private Integer weight;

        private List<MentionVO> evidence;
    }
}