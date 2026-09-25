package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 只读 Agent 的结果（Python {@code AgentAskResult}）。
 *
 * <p>**别把 `doneReason=length` 当成失败**：它表示预算触顶而没能在预算内收敛。
 * 此时 `answer` 可能为空，但 `citations` 很可能有值 —— 前端要显示
 * 「查到了这些，但没能在预算内给出结论」，而不是一片空白。
 * 这也是为什么 Java 侧不做「answer 为空就抛错」这种看似贴心的处理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAskResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 最终答案；预算触顶或中断时可能为空 */
    private String answer;

    /** 引用；全部来自工具结果，片段与分数由服务端补齐 */
    private List<CitationDTO> citations;

    /** stop（答完）/ length（预算触顶）/ cancelled（调用方中断）/ error / refused */
    private String doneReason;

    /** 逐步审计记录 */
    private List<AgentStepDTO> steps;

    /** 实际调用工具的次数 */
    private Integer toolCalls;

    /** `caller` 或 `budget`，未中断时为空 */
    private String interruptedBy;

    private String usageModel;

    private Long latencyMs;
}
