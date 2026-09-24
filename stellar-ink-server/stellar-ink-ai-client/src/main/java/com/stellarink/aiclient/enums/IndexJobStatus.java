package com.stellarink.aiclient.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 索引任务状态；{@code PARTIAL} 表示部分文章失败但仍可查询结果。 */
public enum IndexJobStatus {

    PENDING("pending"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    PARTIAL("partial"),
    FAILED("failed");

    private final String value;

    IndexJobStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
