package com.stellarink.sharedmodel.enums;

import lombok.Getter;

import java.util.Locale;

/**
 * 技术笔记类型：驱动列表与详情的视觉符号，不参与权限判定。
 *
 * <p>与「技术栈标签」（note.tags，如 java/spring-boot）是两组正交维度：
 * 类型回答「这是什么性质的笔记」，标签回答「它是关于什么的」。
 */
@Getter
public enum NoteType {

    /** 问题解决：现象 → 结论，最常用的一类 */
    FIX("问题解决", "❖"),

    /** 踩坑记录：做错了什么、代价是什么、以后怎么避 */
    PITFALL("踩坑记录", "⚠"),

    /** 学习笔记：某个机制/原理的理解与推导 */
    TIL("学习笔记", "✦"),

    /** 碎片：还没有结论的观察与线索 */
    SCRAP("碎片", "☄");

    /** 中文名 */
    private final String label;

    /** 列表/详情使用的视觉符号 */
    private final String glyph;

    NoteType(String label, String glyph) {
        this.label = label;
        this.glyph = glyph;
    }

    /** 严格解析（大小写不敏感），无法识别返回 {@code null} */
    public static NoteType parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return NoteType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 宽容解析：无法识别时回退为 {@link #FIX}（缺省即最常用类型） */
    public static NoteType parseOrDefault(String value) {
        NoteType type = parse(value);
        return type == null ? FIX : type;
    }
}
