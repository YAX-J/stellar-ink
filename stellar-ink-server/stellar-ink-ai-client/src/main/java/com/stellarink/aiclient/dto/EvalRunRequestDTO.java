package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 评测运行请求（{@code EvalRunRequest}）：AI 实验室「评测台」页签点「跑一轮」时发出。
 *
 * <p>契约样例见 {@code stellar-ink-ai/tests/fixtures/eval_run_request.json}，两侧测试共读同一份。
 * {@code strategies} 留空时由 Python 用**标准五组**（与命令行脚本同源），
 * 因此面板可以「先跑默认，再按需改开关」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRunRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 数据集标识，当前只有 {@code golden_v1} */
    private String dataset;

    /** 被测配置；为空时用 Python 侧的标准五组 */
    private List<EvalStrategySpecDTO> strategies;

    /** 只跑前 N 题（调试用；为空则全跑） */
    private Integer maxCases;
}
