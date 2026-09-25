package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 评测运行结果（{@code EvalRunResponse}）：对比表 + 逐题明细 + 数据来源说明。
 *
 * <p>为什么 {@code perStrategy} 用 {@code Map<String, Object>}：
 * 指标集合由 Python 侧 {@code evaluate_strategy} 决定（Recall@K / NDCG / 拒答率 / 延迟……），
 * Java 再定义一遍就等于把指标名写死两处；面板本来就按「列名」渲染，
 * 结构化到 DTO 反而是负担。**但契约测试会校验它的形状**（见 {@code AiContractTest}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRunResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 数据集展示名 */
    private String dataset;

    /** 数据集说明 */
    private String datasetDescription;

    /** 语料来源（例：{@code seed-sql:02-init-data.sql}） */
    private String corpusSource;

    /** 语料文章数 */
    private Integer corpusPosts;

    /** 语料子块数 */
    private Integer corpusChunks;

    /** 模型来源：当前固定 {@code fake}（Dense 两列不代表真实语义质量） */
    private String models;

    /** 评估用到的 K */
    private List<Integer> ks;

    /** 本次跑的策略（顺序即对比表列序） */
    private List<EvalStrategySummaryDTO> strategies;

    /** {策略: 指标} —— 面板的对比表 */
    private Map<String, Map<String, Object>> perStrategy;

    /** 逐题明细 */
    private List<EvalCaseResultDTO> cases;

    /** 整轮耗时（毫秒） */
    private Double elapsedMs;

    /** 诚实提示：这些数字能说明什么、不能说明什么（面板要原文展示） */
    private List<String> notes;
}
