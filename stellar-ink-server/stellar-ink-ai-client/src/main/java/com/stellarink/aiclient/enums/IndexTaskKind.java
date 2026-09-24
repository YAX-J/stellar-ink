package com.stellarink.aiclient.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 索引任务类型。MVP 只有全量重建与单篇重建（roadmap §7 数据一致性）。 */
public enum IndexTaskKind {

    FULL_REBUILD("full_rebuild"),
    POST_REBUILD("post_rebuild");

    private final String value;

    IndexTaskKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
