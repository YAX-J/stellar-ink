package com.stellarink.aiclient.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 生成结束原因，取值与 Python 契约的 {@code DoneReason} 一致。
 *
 * <p>{@code REFUSED} 是证据不足时的明确拒答：必须如实告诉用户「文章中没有找到依据」，
 * 不允许用编造的答案填充（见 docs/ai/README.md 的安全规则）。
 */
public enum DoneReason {

    STOP("stop"),
    LENGTH("length"),
    REFUSED("refused"),
    CANCELLED("cancelled"),
    ERROR("error");

    private final String value;

    DoneReason(String value) {
        this.value = value;
    }

    /** 序列化用的小写取值：必须与 Python 契约逐字一致（默认会写成 STOP）。 */
    @JsonValue
    public String value() {
        return value;
    }
}
