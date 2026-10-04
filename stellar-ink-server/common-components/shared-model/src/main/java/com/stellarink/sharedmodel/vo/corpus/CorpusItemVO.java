package com.stellarink.sharedmodel.vo.corpus;

import com.stellarink.sharedmodel.enums.CorpusKind;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 语料清单里的**一条**（不含正文）。
 *
 * <p>为什么清单不带正文：对账只需要「有哪些文档、各自的版本是什么」。
 * 正文可能很长，只为算出「哪几篇变了」把整库正文搬一遍是浪费；
 * 需要正文时按 {@code kind + id} 单独取（见 {@link CorpusContentVO}）。
 */
@Data
public class CorpusItemVO {

    /** 文档种类：post（文章）/ note（笔记）。 */
    private CorpusKind kind;

    /** 该种类下的主键（文章 id 或笔记 id）。 */
    private Long id;

    private String title;

    /**
     * **整篇**内容哈希（标题 + 正文的 SHA-256，见 {@code com.stellarink.common.util.Hashes#docHash}）。
     *
     * <p>⚠️ 与「索引里每个子块的 contentHash」（Python 切块时算的）**不是一回事**：
     * 这个是「这篇文章有没有变」的廉价判据，那个是「索引锚点还对得上原文吗」。
     */
    private String docHash;

    /** 最后修改时间（增量拉取的游标）。 */
    private LocalDateTime updatedAt;
}
