package com.stellarink.domain.enums;

import lombok.Getter;

/**
 * 友链状态：申请后待站长确认
 */
@Getter
public enum LinkStatus {

    PENDING(0, "待确认"),
    ACCEPTED(1, "已接入");

    private final int value;
    private final String label;

    LinkStatus(int value, String label) {
        this.value = value;
        this.label = label;
    }
}
