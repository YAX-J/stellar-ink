package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个实体（表 {@code ai_wiki_entity}，见 {@code deploy/sql/14_ai_wiki_entity.sql}）。
 *
 * <p>幂等锚点是 {@code normalized}（归一化后的名字）：「每天写五百字」与「　每天写五百字 」
 * 是同一个实体，只占一行。{@code name} 只是**代表写法**（出现最多的那种）。
 *
 * <p>{@code mentionCount} / {@code postCount} 是冗余计数：列表要按「被谈论得多不多」排序，
 * 每次都去 count 一遍关联表既慢又容易与明细不一致（重建时同事务更新）。
 */
@Data
@TableName("ai_wiki_entity")
public class AiWikiEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String normalized;

    private String name;

    private String kind;

    private Integer mentionCount;

    private Integer postCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
