package com.stellarink.aiclient.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 写作任务类型，与前端 Copilot 的功能一一对应。取值与 Python 契约一致。 */
public enum WritingTask {

    TITLE("title"),
    OUTLINE("outline"),
    CONTINUE("continue"),
    POLISH("polish"),
    TAGS("tags"),
    SUMMARY("summary");

    private final String value;

    WritingTask(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
