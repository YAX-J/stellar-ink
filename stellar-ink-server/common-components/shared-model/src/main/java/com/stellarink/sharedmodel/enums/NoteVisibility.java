package com.stellarink.sharedmodel.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Locale;

/**
 * 笔记可见性。这是笔记相对文章新增的一档语义：文章天然公开，笔记大量是写给自己看的。
 *
 * <p>硬性约束（见 AGENTS.md）：{@link #PRIVATE} 的笔记除了作者本人，任何人读详情都返回 404
 * （与文章草稿同一套防枚举做法），且不得出现在公开列表、标签统计与搜索里。
 * <b>ADMIN 也不能读他人私有笔记</b> ——「私有」必须是对作者的保证。
 */
@Getter
@RequiredArgsConstructor
public enum NoteVisibility {

    /** 私有：仅作者本人可见 */
    PRIVATE("私有", false),

    /** 公开：出现在笔记列表与技术栈热度里 */
    PUBLIC("公开", true);

    /** 中文名 */
    private final String label;

    /** 是否对外可见 */
    private final boolean publiclyVisible;

    /** 严格解析（大小写不敏感），无法识别返回 {@code null} */
    public static NoteVisibility parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return NoteVisibility.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 宽容解析：无法识别时回退为 {@link #PRIVATE}。
     * 缺省取最保守的一档 —— 写错的可见性参数只会让笔记更隐蔽，不会意外泄漏。
     */
    public static NoteVisibility parseOrDefault(String value) {
        NoteVisibility visibility = parse(value);
        return visibility == null ? PRIVATE : visibility;
    }

    /**
     * 新建笔记时的默认可见性：**未显式指定**（{@code null} / 空白）取 {@link #PUBLIC}。
     *
     * <p>技术笔记的价值在于被读到、被搜到、被复用；默认私有会让「写了很多、没人看得见」
     * 变成一种沉默的默认结果 —— 这正是它此前只能算「私人标本册」而不是技术博客的原因。
     *
     * <p>但**写错的值**（例如客户端传了 "publick"）仍回退 {@link #PRIVATE}：
     * 那是客户端 bug 的信号，宁可让这条笔记更隐蔽，也不能因为一个拼写错误把它公开出去。
     * 所以这里刻意不复用 {@link #parseOrDefault} —— 两者对 null 的取舍相反，
     * 混用会把「没填」与「填错」当成同一件事。
     */
    public static NoteVisibility defaultForCreate(String value) {
        return value == null || value.isBlank() ? PUBLIC : parseOrDefault(value);
    }
}
