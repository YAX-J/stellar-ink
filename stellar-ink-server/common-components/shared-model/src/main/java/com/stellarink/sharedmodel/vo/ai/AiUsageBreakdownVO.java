package com.stellarink.sharedmodel.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 调用账的一个分组（按场景或按模型聚合）。
 *
 * <p>{@code unpricedCalls} 与 {@code untokenizedCalls} 是刻意留的两个「缺口计数」：
 * 看板上「花了多少钱」只有在缺口为 0 时才是完整答案。少了这两列，
 * 一张缺了单价或没拿到用量的账会安静地显示成一个偏小的金额 —— 那比报错更难发现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiUsageBreakdownVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 分组键：场景名（qa/agent/…）或模型名；模型未知时为 {@code (未记录)} */
    private String key;

    private Integer calls;

    private Long promptTokens;

    private Long completionTokens;

    private Long totalTokens;

    /** 已定价部分的成本（元）；缺口计数非 0 时它只是**下限** */
    private BigDecimal cost;

    /** 有 token 但没配单价的调用数（成本里不含它们） */
    private Integer unpricedCalls;

    /** 上游没回报 token 的调用数（不是 0，是「不知道」） */
    private Integer untokenizedCalls;
}
