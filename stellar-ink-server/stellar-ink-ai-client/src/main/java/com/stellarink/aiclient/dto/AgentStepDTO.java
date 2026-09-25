package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 只读 Agent 的一步审计记录（Python {@code AgentStepView}）。
 *
 * <p>这一层**只转发**：`thought` / `label` / `error` 都是给人看的原文，
 * Java 不解析、不裁剪、不翻译 —— 一旦在这里加工，「前端看到的审计」与
 * 「Python 记录的审计」就成了两份东西，出问题时对不上。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 第几步（从 0 开始） */
    private Integer index;

    /** 模型给出的这一步的理由（仅供审计） */
    private String thought;

    /** 调用的工具名；收尾步为空 */
    private String tool;

    /** 结果的短标签，如「检索到 4 段」 */
    private String label;

    /** 这一步的问题（格式不符 / 工具不存在 / 预算触顶） */
    private String error;
}
