package com.stellarink.sharedmodel.enums;

import java.util.Locale;

/**
 * 技术笔记复核状态。
 *
 * <p>{@link #DUE} 仅用于查询，表示「从未验证或已过期」；列表项本身只会返回
 * {@link #UNVERIFIED}、{@link #EXPIRED} 或 {@link #FRESH}。
 */
public enum NoteReviewState {

    /** 待复核：未验证与已过期的合集 */
    DUE,

    /** 从未验证 */
    UNVERIFIED,

    /** 上次验证已超过 180 天 */
    EXPIRED,

    /** 仍在 180 天有效期内 */
    FRESH;

    /** 大小写不敏感；空值或非法值默认回到待复核队列。 */
    public static NoteReviewState parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return DUE;
        }
        try {
            return NoteReviewState.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DUE;
        }
    }
}
