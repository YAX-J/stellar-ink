package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 主题包含哪个实体（表 {@code ai_wiki_topic_entity}）。 */
@Data
@TableName("ai_wiki_topic_entity")
public class AiWikiTopicEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long topicId;

    private Long entityId;

    private LocalDateTime createdAt;
}