package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个主题（表 {@code ai_wiki_topic}）：共现图上的连通分量。
 *
 * <p><b>幂等锚点是 {@code signature}</b>（成员规范化名字排序后拼接的 SHA-256），**不是名字**：
 * 主题名（关键词组合）由成员算出来，成员一变名字就变 —— 拿名字当锚点会凭空多出一行，
 * 看起来像「发现了新主题」。成员变了本来就该是新主题，旧的那行留着，
 * 它记录的是当时的知词状态。
 */
@Data
@TableName("ai_wiki_topic")
public class AiWikiTopic {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String signature;

    /** 关键词组合（由成员算出来，不是模型拟的标题） */
    private String name;

    /** 前几个关键词，逗号分隔（列表展示用） */
    private String keywords;

    private Integer size;

    /** 主题内共现边总权重 */
    private Integer weight;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}