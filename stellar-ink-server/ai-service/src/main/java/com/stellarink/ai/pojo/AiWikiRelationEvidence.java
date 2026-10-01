package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条共现关系的证据（表 {@code ai_wiki_relation_evidence}）：
 * 「哪篇文章的哪句话同时提到了这两个实体」。
 *
 * <p>没有它，图上就会有一批「凭空的连线」—— 而图看起来最像真的。
 */
@Data
@TableName("ai_wiki_relation_evidence")
public class AiWikiRelationEvidence {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long relationId;

    private Long postId;

    private Integer chunkIndex;

    private String claimText;

    private LocalDateTime createdAt;
}
