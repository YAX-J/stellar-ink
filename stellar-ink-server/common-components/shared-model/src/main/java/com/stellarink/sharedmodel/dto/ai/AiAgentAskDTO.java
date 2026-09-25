package com.stellarink.sharedmodel.dto.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 面向浏览器的只读 Agent 请求（{@code POST /ai/agent/ask}）。
 *
 * <p>预算字段（`maxSteps` / `maxToolCalls`）**允许前端收紧**：调试时只想跑一步是合理需求。
 * 但上限被 `@Max` 卡死，且服务端还会再取一次 min —— 放宽预算这件事不能由客户端说了算，
 * 否则「预算」就只是个装饰。
 */
@Data
public class AiAgentAskDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "question 不能为空")
    @Size(max = 500, message = "问题不能超过 500 字")
    private String question;

    /** 最多推理几步（含收尾那一步）；留空用服务端默认 */
    @Min(value = 1, message = "maxSteps 至少为 1")
    @Max(value = 8, message = "maxSteps 最多为 8")
    private Integer maxSteps;

    /** 最多调用几次工具；留空用服务端默认 */
    @Min(value = 1, message = "maxToolCalls 至少为 1")
    @Max(value = 12, message = "maxToolCalls 最多为 12")
    private Integer maxToolCalls;
}
