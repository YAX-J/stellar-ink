package com.stellarink.sharedmodel.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 一个实体（读者侧视图，E4-6）。
 *
 * <p>{@code mentions} 与 {@code relations} 一起给出，是因为**实体页要能自己站得住**：
 * 光有「实体名 + 出现次数」等于让人相信一个数字；
 * 带上「它出现在哪几句主张里」与「它和谁被一起谈论」才是可核对的东西。
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

    private String kind;

    private Integer mentionCount;

    private Integer postCount;

    /** 它出现在哪几句主张里 */
    private List<AiWikiEntityMentionVO> mentions;

    /** 它与谁被一起谈论（**共现**，不是语义关系），按权重降序 */
    private List<AiWikiRelationVO> relations;

    /** 一次提及：回到某篇文章的某句主张。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiWikiEntityMentionVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long postId;

        private Integer chunkIndex;

        private String claimText;
    }

    /** 一条共现关系：另一端实体 + 权重 + 证据。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiWikiRelationVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 另一端实体的 id */
        private Long entityId;

        /** 另一端实体的名字（读者要看的） */
        private String name;

        private Integer weight;

        private List<AiWikiEntityMentionVO> evidence;
    }
}
