package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 逐题评测明细（{@code EvalCaseResultRow}）。
 *
 * <p>面板用它做下钻：「这题为什么没召回」看 {@code retrievedPosts} 与 {@code relevantPosts} 的差集；
 * 「为什么拒答」看 {@code refused} 与 {@code caseType}（无答案题拒答才是对的）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCaseResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 题目 ID */
    private String caseId;

    /** 策略 key（同一题会被多组策略各跑一次） */
    private String strategy;

    /** 问题原文 */
    private String question;

    /** {@code answerable} | {@code unanswerable} */
    private String caseType;

    /** 检索到的文章（按名次） */
    private List<Long> retrievedPosts;

    /** 标注的相关文章 */
    private List<Long> relevantPosts;

    /** 本题是否拒答 */
    private Boolean refused;

    /** 单题耗时（毫秒） */
    private Double latencyMs;
}
