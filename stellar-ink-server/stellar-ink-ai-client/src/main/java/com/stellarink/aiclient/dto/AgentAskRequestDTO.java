package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 只读 Agent 的请求（Python {@code AgentAskRequest}）。
 *
 * <p>`maxSteps` / `maxToolCalls` 由 ai-service 决定并传下来，**客户端只能收紧不能放宽** ——
 * 预算是硬上限，让前端随便传一个 999 就等于没有预算。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAskRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String question;

    private Integer maxSteps;

    private Integer maxToolCalls;
}
