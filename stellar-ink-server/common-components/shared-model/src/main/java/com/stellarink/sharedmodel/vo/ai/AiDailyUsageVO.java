package com.stellarink.sharedmodel.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 当日用量按「模型角色 + 场景」的聚合（供指标看板用，不是对外接口的返回体）。
 *
 * <p><b>为什么要单独一个 VO、而不是让指标层自己去查 {@code ai_call_log}</b>：
 * 成本口径只有一份 —— 「算不出来返回 {@code null} 而不是 0」「部分定价也算算不出来」
 * 「失败调用不进 token/成本统计」这些规则写在 {@code AiUsageServiceImpl} 里。
 * 指标层若自己再算一遍，早晚会和 {@code /ai/admin/usage/summary} 上的数字对不上，
 * 而**两个看板给出两个成本数字**是最难解释的一类问题。
 *
 * <p>两个缺口计数与 {@link AiUsageBreakdownVO} 同义：
 * {@code unpricedCalls} 是「知道用了多少但没配单价」，{@code untokenizedCalls} 是
 * 「上游没回报用量」。只要任一非 0，{@code cost} 就只是**下限**。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiDailyUsageVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 调用场景：qa / qa_stream / writing_suggest / agent / eval；缺失时为 {@code (未记录)} */
    private String scene;

    /** 用到的模型角色：chat / embedding / rerank；评测一次用多个模型，故可能缺失 */
    private String providerRole;

    /** 当日调用数（**含失败**）：失败也要计数，否则失败率无从统计 */
    private Integer calls;

    private Integer successCalls;

    private Integer failedCalls;

    /** 当日 token 合计（只统计成功调用 —— 失败的用量本来就缺失或不可信） */
    private Long totalTokens;

    /** 已定价部分的成本（**元**）；两个缺口计数非 0 时它只是下限 */
    private BigDecimal cost;

    /** 有 token 但没配单价的调用数（成本里不含它们） */
    private Integer unpricedCalls;

    /** 上游没回报 token 的调用数（不是 0，是「不知道」） */
    private Integer untokenizedCalls;
}
