package com.stellarink.sharedmodel.dto.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

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
}
