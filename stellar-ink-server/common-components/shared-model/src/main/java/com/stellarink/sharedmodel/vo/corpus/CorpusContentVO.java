package com.stellarink.sharedmodel.vo.corpus;

import com.stellarink.sharedmodel.enums.CorpusKind;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 语料的**单篇正文**（嵌入前要用的完整内容）。
 *
 * <p>只有「已发布且公开」的文档才可能被读到：草稿、已删除、私有笔记在这里一律 404 ——
 * 即便调用方拿着一个正确的 id 来问。这是「私有内容不许进知识库」这条口径的**第二道**闸门
 * （第一道是清单里根本不出现）。
 */
@Data
public class CorpusContentVO {

    private CorpusKind kind;

    private Long id;

    private String title;

    /** 正文（Markdown 原文，未做任何清理 —— 清理与切块都属于 Python 侧）。 */
    private String content;

    /** 标签原文（逗号分隔，可能为空）。 */
    private String tags;

    /** 与清单里同一个整篇哈希：调用方可以据此确认「拿到的这版就是清单里那版」。 */
    private String docHash;

    private LocalDateTime updatedAt;
}
