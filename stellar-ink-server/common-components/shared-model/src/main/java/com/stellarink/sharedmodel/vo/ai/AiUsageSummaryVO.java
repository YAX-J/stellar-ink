package com.stellarink.sharedmodel.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 用量与成本汇总（{@code GET /ai/admin/usage/summary}，ADMIN）。
 *
 * <p>读法只有一句：<b>{@code cost} 是「已定价部分」的合计，先看两个缺口计数再看金额</b>。
 * {@code unpricedCalls}（没配单价）与 {@code untokenizedCalls}（上游没回报 token）任一非 0，
 * 金额就只是下限，不能当「这个月花了这么多」用。
 *
 * <p>统计口径：{@code calls} / {@code successCalls} / {@code failedCalls} 覆盖全部调用；
 * **token、成本与两个缺口计数只统计成功调用** —— 失败调用的用量本来就不存在，
 * 混进去会把「上游没回报用量」显示成「失败很多」。
 *
 * <p>这也解释了为什么单价必须快照进账：事后改一次单价，历史账目的金额会集体变化，
 * 而看板上不会有任何迹象说明「这个数被改写过了」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiUsageSummaryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 统计窗口天数（与请求参数一致，便于前端回显） */
    private Integer days;

    /** 窗口起点（含），按服务器时区 */
    private LocalDateTime since;

    private Integer calls;

    private Integer successCalls;

    private Integer failedCalls;

    private Long promptTokens;

    private Long completionTokens;

    private Long totalTokens;

    /** 已定价部分的成本（元）；缺口非 0 时是下限 */
    private BigDecimal cost;

    /** 有 token 但没配单价的调用数 */
    private Integer unpricedCalls;

    /** 上游没回报 token 的调用数（Agent 与评测当前都属于这类） */
    private Integer untokenizedCalls;

    private List<AiUsageBreakdownVO> byScene;

    private List<AiUsageBreakdownVO> byModel;
}
