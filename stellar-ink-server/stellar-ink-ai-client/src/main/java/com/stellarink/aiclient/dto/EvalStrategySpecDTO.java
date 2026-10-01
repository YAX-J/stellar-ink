package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一组被测检索配置（{@code EvalStrategySpec}）：字段与 Python 侧 {@code RetrievalConfig} 一一对应。
 *
 * <p>Java 侧**不做任何算法判断**，只把面板上的开关原样传给 Python ——
 * 哪个开关叫什么名字、默认值是多少，都以 Python 的契约为准（改这里必须同步改那边）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalStrategySpecDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 策略标识：对比表的列名，同一次请求内必须唯一 */
    private String key;

    /** 启用 BM25 召回 */
    private Boolean enableSparse;

    /** 启用向量召回 */
    private Boolean enableDense;

    /** 启用重排（只对候选做） */
    private Boolean enableRerank;

    /** 评估深度（算 Recall@K 用） */
    private Integer topK;

    /** 每路召回的候选数 */
    private Integer candidateK;

    /** RRF 里 BM25 的权重 */
    private Double sparseWeight;

    /** RRF 里向量的权重 */
    private Double denseWeight;

    /** RRF 的 k（越大越弱化头部名次） */
    private Integer rrfK;

    /** BM25 绝对下限：唯一能触发拒答的机制 */
    private Double minScore;

    /** BM25 相对门限：只提精度，永远不会让结果为空 */
    private Double minScoreRatio;

    /** 余弦下限：Dense 通路的拒答机制 */
    private Double minDenseScore;

    /** 重排后保留的候选数 */
    private Integer rerankTopN;

    /**
     * 启用图检索（E5-2）：走知识图的实体与共现边，**一次模型都不调**。
     *
     * <p>它需要请求里带上 {@code graph}（一次 {@code /wiki/claims} 返回体）；
     * 没带时 Python 会如实回一条「本次没带图」的行，而不是静悄悄少一列 ——
     * 那一行看起来像「图检索效果为零」，实际是「根本没跑」。
     */
    private Boolean enableGraph;
}
