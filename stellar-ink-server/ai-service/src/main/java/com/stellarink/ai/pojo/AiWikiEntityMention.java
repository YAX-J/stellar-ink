package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次实体出现（表 {@code ai_wiki_entity_mention}）：它挂在哪条主张上。
 *
 * <p>为什么不是「实体 ↔ 文章」的粗粒度关联：{@code claimText} 让每个提及都能回到
 * **一句具体主张**（而不是「大概在这篇文章里出现过」）。少了它，实体就无从核对 ——
 * 而「能核对」正是 Wiki 与「模型印象集」的区别。
 */
@Data
@TableName("ai_wiki_entity_mention")
public class AiWikiEntityMention {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long entityId;

    private Long postId;

    private Integer chunkIndex;

    private String claimText;

    private LocalDateTime createdAt;
}
