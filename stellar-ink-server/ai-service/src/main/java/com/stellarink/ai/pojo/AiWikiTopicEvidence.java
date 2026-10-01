package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主题证据（表 {@code ai_wiki_topic_evidence}）：主题页上那段可核对的原文。
 *
 * <p>没有它，主题页就是一堆关键词 —— 而关键词谁都能拼。
 */
@Data
@TableName("ai_wiki_topic_evidence")
public class AiWikiTopicEvidence {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long topicId;

    private Long postId;

    private Integer chunkIndex;

    private String claimText;

    private LocalDateTime createdAt;
}