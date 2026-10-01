package com.stellarink.sharedmodel.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 一个主题页（读者侧视图，E4-9）：一组被反复一起谈论的实体 + 可核对的原文。
 *
 * <p>{@code name} 是**关键词组合**（由成员实体名拼成），不是模型拟的标题 ——
 * 它读起来不像一句话，但它总是诚实的：名字就是这页里的东西。
 *
 * <p>{@code evidence} 是这一页的可核对部分：每段文字都能回到某篇文章的某句主张。
 * 没有它，主题页就只是「一堆看起来相关的词」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiTopicVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private List<String> keywords;

    /** 成员实体个数 */
    private Integer size;

    /** 主题内共现边总权重 */
    private Integer weight;

    private List<Long> postIds;

    /** 成员实体（按提及数降序、名字升序 —— 顺序确定） */
    private List<EntityBriefVO> entities;

    /** 主题页上那段可核对的原文 */
    private List<EvidenceVO> evidence;

    /** 成员实体的简要信息（页面上要显示名字与类型，不显示 id）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityBriefVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long id;

        private String name;

        private String kind;

        /** 全站：它出现在几条主张里 */
        private Integer mentionCount;
    }

    /** 一段可核对的原文。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long postId;

        private Integer chunkIndex;

        private String claimText;
    }
}
