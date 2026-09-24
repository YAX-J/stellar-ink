package com.stellarink.aiclient.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * AI 能力对外的可展示错误码，取值与 Python 契约的 {@code AiErrorCode} 一致。
 *
 * <p>为什么单独一套而不是复用 {@code ErrorCode}：AI 的失败形态更细
 * （上游不可用 / 超时 / 限流 / 拒答），前端要按码给不同文案与降级路径，
 * 混进通用错误码会分不清「接口错了」还是「模型暂时不可用」。
 *
 * <p>约定：Python 侧错误体形如 {@code "AI_UPSTREAM_UNAVAILABLE: 上游超时"}，
 * Java 解析时取冒号前的码（见 {@link #parse(String)}），无法识别时回退 {@code AI_INTERNAL}。
 */
public enum AiErrorCode {

    BAD_REQUEST("AI_BAD_REQUEST"),
    UNAUTHORIZED("AI_UNAUTHORIZED"),
    FORBIDDEN("AI_FORBIDDEN"),
    RATE_LIMITED("AI_RATE_LIMITED"),
    UPSTREAM_UNAVAILABLE("AI_UPSTREAM_UNAVAILABLE"),
    TIMEOUT("AI_TIMEOUT"),
    INTERNAL("AI_INTERNAL");

    private final String value;

    AiErrorCode(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    /** 从错误体/错误消息里解析错误码；识别不出时回退 {@code AI_INTERNAL}。 */
    public static AiErrorCode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return INTERNAL;
        }
        String code = raw.contains(":") ? raw.substring(0, raw.indexOf(':')).trim() : raw.trim();
        for (AiErrorCode candidate : values()) {
            if (candidate.value.equals(code)) {
                return candidate;
            }
        }
        return INTERNAL;
    }
}
