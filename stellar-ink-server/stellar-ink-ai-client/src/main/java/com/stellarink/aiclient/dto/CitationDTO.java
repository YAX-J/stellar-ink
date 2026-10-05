package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/** 引用：必须能定位回原文的段落（M3 起由检索结果填充）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 内容种类：{@code post}（文章，前端跳 {@code /read/:id}）或 {@code note}（技术笔记，跳
     * {@code /note/:id}）。
     *
     * <p><b>文档标识是 {@code kind + postId}</b>：文章 3 与笔记 3 是两篇不同的内容。
     * 用字符串而不是枚举是刻意的 —— 取值由 Python 侧给出（小写字面量），
     * 这里原样透传给浏览器，不在 Java 侧做任何映射。</p>
     */
    private String kind;

    /** 该 {@code kind} 下的文档 ID（文章 ID 或笔记 ID） */
    private Long postId;

    private String title;

    /** 段落序号，从 0 开始 */
    private Integer chunkIndex;

    /** 引用的原文片段 */
    private String snippet;

    /** 检索/重排得分；为空表示该来源未参与打分 */
    private Double score;
}
