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
 *
 * <p>A1 起多一个 `agent`（司职）：`answerer` / `searcher` / `verifier`。
 * 它是**稳定标识**，Python 按它取提示词、工具白名单与预算上限；未知名字会被 Python
 * 以 422 拒掉（**不猜、不回退默认**），消息里列出可选值，由 `PythonErrorDecoder`
 * 原样交给用户。Java 侧不维护司职清单：多一份清单就多一处需要同步的东西。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAskRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String question;

    /** 司职名（answerer / searcher / verifier）；留空时由 Python 取缺省司职 */
    private String agent;

    private Integer maxSteps;

    private Integer maxToolCalls;
}
