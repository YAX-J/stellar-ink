package com.stellarink.sharedmodel.dto.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 面向浏览器的写作建议请求（{@code POST /ai/writing/suggest}）。
 *
 * <p>`task` / `tone` 用字符串而不是枚举：`shared-model` 不该依赖 `stellar-ink-ai-client`
 * 的枚举（依赖方向是反的），因此这里做字面量校验、由 ai-service 转成内部枚举。
 * 取值与 Python 契约 `WritingTask` / `WritingTone` 逐字一致。
 *
 * <p>**草稿是作者的私有内容**：它只随本次请求进入 Python，不入索引、不落库；
 * 本接口只返回候选，绝不改写正文 —— 采纳与否由作者在差异预览里决定。
 */
@Data
public class AiWritingSuggestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** title / outline / continue / polish / tags / summary */
    @NotBlank(message = "task 不能为空")
    @Pattern(
            regexp = "title|outline|continue|polish|tags|summary",
            message = "task 只支持 title/outline/continue/polish/tags/summary")
    private String task;

    /** 当前草稿；continue/polish/tags/summary 必须非空（由 Python 契约复核） */
    @Size(max = 20000, message = "草稿不能超过 20000 字")
    private String draft = "";

    /** 附加要求（可选）；与 tone 同时存在时以它为准 */
    @Size(max = 500, message = "附加要求不能超过 500 字")
    private String instruction;

    /** keep / restrained / colloquial / concise */
    @Pattern(
            regexp = "keep|restrained|colloquial|concise",
            message = "tone 只支持 keep/restrained/colloquial/concise")
    private String tone = "keep";

    @Min(value = 1, message = "candidateCount 至少为 1")
    @Max(value = 5, message = "candidateCount 最多为 5")
    private Integer candidateCount = 3;
}
