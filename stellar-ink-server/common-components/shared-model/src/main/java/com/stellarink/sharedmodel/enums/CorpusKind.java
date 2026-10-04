package com.stellarink.sharedmodel.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 语料条目的种类：文章 / 技术笔记。
 *
 * <p>为什么要有这个枚举：RAG 语料来自两张表（`post`、`note`），而它们的 id 各自自增 ——
 * 「3 号文章」与「3 号笔记」是两个不同的文档。索引里只存 id 会造成「引用指向另一篇」，
 * 所以 {@code kind + id} 才是文档的完整标识。
 *
 * <p>序列化成小写字面量（{@code "post"} / {@code "note"}），与 Python 侧的
 * {@code kind} 字段口径一致（Java 枚举默认会写成大写，必须靠 {@link JsonValue}）。
 */
public enum CorpusKind {

    /** 文章（`post` 表，已发布）。 */
    POST("post"),

    /** 技术笔记（`note` 表，已发布且 visibility = PUBLIC）。 */
    NOTE("note");

    private final String key;

    CorpusKind(String key) {
        this.key = key;
    }

    @JsonValue
    public String key() {
        return key;
    }

    /** 宽松解析：null / 空白返回 null；非法值抛 IllegalArgumentException（调用方翻成参数错误）。 */
    public static CorpusKind parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (CorpusKind kind : values()) {
            if (kind.key.equals(normalized) || kind.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知的语料种类：" + value + "（只接受 post / note）");
    }
}
