package com.stellarink.sharedmodel.dto.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 面向浏览器的问答请求（{@code POST /ai/qa}）。
 *
 * <p>与内部契约 {@code QaStreamRequestDTO} 刻意分开：内部那个随 Python 契约走，
 * 这个对前端负责（字段名、校验、文档）。两者字段目前相同，但**不要合并** ——
 * 一旦 Python 契约调整（例如加检索过滤），先动的是内部那个，前端的兼容性由这一层决定。
 */
@Data
public class AiAskDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户问题（不超过 500 字；空白串由 {@code @NotBlank} 拦下） */
    @NotBlank(message = "问题不能为空")
    @Size(max = 500, message = "问题不能超过 500 字")
    private String question;

    /** 召回候选数上限；为空表示由 Python 侧决定（当前 5） */
    @Min(value = 1, message = "topK 至少为 1")
    @Max(value = 20, message = "topK 最多为 20")
    private Integer topK;

    /**
     * 最近几轮问答（多轮助手用；单轮问答留空）。
     *
     * <p>上限 6 轮：历史是**语境的参考**，不是内容来源 —— 堆多了会挤掉真正要引用的摘录，
     * 而不是让回答更准。Python 侧另有同口径的上限（两道都要，见 AGENTS §5）。</p>
     */
    @Size(max = 6, message = "最多带 6 轮历史")
    @Valid
    private List<AiHistoryTurnDTO> history;
}
