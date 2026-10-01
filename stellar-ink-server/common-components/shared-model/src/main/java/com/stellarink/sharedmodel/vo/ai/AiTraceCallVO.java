package com.stellarink.sharedmodel.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一条调用账（按 traceId 回放时用）。
 *
 * <p>与 {@code AiUsageBreakdownVO} 的区别：那个是**聚合**（一行代表一类调用），
 * 这个是**单条**（一行代表一次调用），所以能看到谁都看不到的东西 ——
 * 具体哪一步失败了、失败分类是什么、耗时多少。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTraceCallVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String scene;

    private String providerRole;

    private String model;

    private Long userId;

    private String role;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer latencyMs;

    /** 1 成功 / 0 失败 */
    private Integer success;

    /** 失败分类（异常类名）；成功时为空 */
    private String errorCode;

    private LocalDateTime createdAt;
}
