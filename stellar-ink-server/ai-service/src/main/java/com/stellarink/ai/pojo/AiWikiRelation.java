package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条实体**共现**关系（表 {@code ai_wiki_relation}）。
 *
 * <p>⚠️ 它是共现（同一句主张里同时出现），**不是语义关系**（因果/属于/依赖）：
 * 后者要模型抽取 + 人工审核。表名与字段名都按「共现」来，别在界面上说成因果关系。
 *
 * <p>无向边只有一种表示：写入前两端按 normalized 排序，
 * 因此唯一键 (source, target) 不会出现 (A,B) 与 (B,A) 各一行（那会让权重看着只有一半）。
 */
@Data
@TableName("ai_wiki_relation")
public class AiWikiRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 字典序较小的一端 */
    private Long sourceEntityId;

    private Long targetEntityId;

    /** 被一起谈论的主张条数 */
    private Integer weight;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
