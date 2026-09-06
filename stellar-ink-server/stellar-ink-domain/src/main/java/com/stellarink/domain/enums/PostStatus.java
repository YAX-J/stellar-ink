package com.stellarink.domain.enums;

import lombok.Getter;

/**
 * 文章状态：星图上只展示已发布（已点亮）的星
 */
@Getter
public enum PostStatus {

    DRAFT(0, "草稿"),
    PUBLISHED(1, "已发布");

    private final int value;
    private final String label;

    PostStatus(int value, String label) {
        this.value = value;
        this.label = label;
    }
}
