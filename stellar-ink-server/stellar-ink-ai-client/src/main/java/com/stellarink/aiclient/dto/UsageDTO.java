package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/** 用量与耗时：M2 起由 Provider 回填，M0/M1 的 Fake 链路也必须给出确定值。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /** 端到端耗时（毫秒） */
    private Integer latencyMs;

    /** 实际使用的模型标识；M0 为 fake-* 前缀 */
    private String model;
}
