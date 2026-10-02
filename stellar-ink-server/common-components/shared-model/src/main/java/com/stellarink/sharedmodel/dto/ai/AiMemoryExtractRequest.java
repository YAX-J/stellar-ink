package com.stellarink.sharedmodel.dto.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 抽取记忆候选的请求（M9）。
 *
 * <p>⚠️ **没有 userId 字段**：身份只从登录态取。有了这个参数，
 * 「替我抽一下用户 X 的记忆」就是一个越权入口。
 */
@Data
public class AiMemoryExtractRequest {

    /** 对话原文（当前对话只是**工作记忆**，roadmap M9 第 1 条要求限制长度）。 */
    @NotBlank(message = "对话内容不能为空")
    @Size(max = 8000, message = "对话太长了（最多 8000 字）—— 当前对话只是工作记忆")
    private String conversation;

    @Min(value = 1, message = "至少抽 1 条")
    @Max(value = 10, message = "一次最多抽 10 条")
    private Integer maxCandidates;
}
