package com.stellarink.ai.corpus.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 语料投影行（`ai_content_snapshot`，见 `deploy/sql/19_ai_content_snapshot.sql`）。
 *
 * <p>它是**投影/缓存**而不是事实源：事实源永远是 content-service 的 `post` / `note`。
 * 所以这一行随时可以被同步过程增、改、删 —— 而上游已删（或已转私有、已下架）的文档，
 * 必须在这里消失，否则它还会被问答引用出来。
 *
 * <p>表里**不存正文**：这张表只回答「有哪些文档、各自什么版本」；
 * 正文在嵌入那一刻才按需向上游单篇取。
 */
@Data
@TableName("ai_content_snapshot")
public class AiContentSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** `post` / `note`（与 {@code CorpusKind} 的小写字面量一致）。 */
    private String kind;

    /** 该种类下的主键（文章 id 或笔记 id）。kind + contentId 才是文档标识。 */
    private Long contentId;

    /** 标题（清单展示用；避免为显示标题再回查上游）。 */
    private String title;

    /**
     * **整篇**（标题+正文）SHA-256：判断「这篇变了没有」，从而避免无谓重嵌。
     *
     * <p>⚠️ 与向量库里**每个子块**的 contentHash（Python 切块时算）不是一回事，不要互比。
     */
    private String docHash;

    /** 字数（上游清单暂未提供时为 0）。 */
    private Integer wordCount;

    /** 上游的最后修改时间（增量拉取的游标）。 */
    private LocalDateTime updatedAt;

    /** 本次同步写入时间。 */
    private LocalDateTime syncedAt;
}
