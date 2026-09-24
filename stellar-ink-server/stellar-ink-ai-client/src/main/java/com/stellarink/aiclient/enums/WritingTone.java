package com.stellarink.aiclient.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 结构化写作目标：把「想要什么风格」变成可枚举、可评测的参数。 */
public enum WritingTone {

    KEEP("keep"),
    RESTRAINED("restrained"),
    COLLOQUIAL("colloquial"),
    CONCISE("concise");

    private final String value;

    WritingTone(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
