package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 一个主题（E4-8，Python → Java）：一组被反复一起谈论的实体。
 *
 * <p>算法是**连通分量 + 边权阈值**（见 `app/rag/topics.py`）：确定、可解释、
 * 失败方式看得见。⚠️ 文章一多，弱关系会把半个知识库连成一片、得到一个巨大的主题；
 * 那时正确的动作是**提高边权阈值**（只保留被反复一起谈论的关系），而不是忍着一个大杂烩 ——
 * 所以 `weight` 与 `size` 都要如实回给调用方，让人看得见自己拿到的是多大的组。
 *
 * <p>`name` 是**关键词组合**（由权重最高的几个实体名拼成），不是模型拟的标题 ——
 * 别指望它读起来像一句话；反过来它总是诚实的：名字就是这页里的东西。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiTopicDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;

    /** 这页讲什么（前几个实体） */
    private List<String> keywords;

    /** 主题内实体的规范化名字，顺序确定 */
    private List<String> entities;

    private Integer size;

    /** 主题内共现边总权重 */
    private Integer weight;

    private List<Long> postIds;

    /** 主题页上那段可核对的原文 */
    private List<EvidenceDTO> evidence;

    /** 主题页上那段可核对的原文。 */
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
