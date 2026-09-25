"""只读 Agent 的契约（E2）。

Agent 的输出**不是答案本身**，而是「答案 + 它做过什么」：
`steps` 里每一条都是一次工具调用或一次格式错误，前端据此展示「它查了哪些东西」。
只给答案的 Agent 在生产里是不可运维的 —— 出了错没人知道它查了什么、在哪一步跑偏。

三条与实现一致的约定：
- `stopReason=length` 表示**预算触顶**，此时 `answer` 可能为空但 `citations` 可能有 ——
  前端要显示「查到这些，但没能在预算内收敛」，而不是一片空白；
- `stopReason=cancelled` 表示调用方中断（用户关页面）；
- 引用只来自工具结果，`citations` 里的片段与分数都是服务端贴回去的。
"""

from typing import Annotated

from pydantic import Field

from app.schemas.base import ContractRequest, ContractResponse
from app.schemas.common import MAX_QUESTION_LENGTH, Citation

#: 与 `app/rag/agent.py` 的默认预算一致；对外允许收紧，不允许放宽
MAX_AGENT_STEPS = 8
MAX_AGENT_TOOL_CALLS = 12


class AgentAskRequest(ContractRequest):
    """``POST /agent/ask`` 的请求体。"""

    question: Annotated[
        str,
        Field(
            min_length=1,
            max_length=MAX_QUESTION_LENGTH,
            description=f"用户问题（不超过 {MAX_QUESTION_LENGTH} 字）",
        ),
    ]

    max_steps: Annotated[
        int,
        Field(default=4, ge=1, le=MAX_AGENT_STEPS, description="最多推理几步（含收尾那一步）"),
    ] = 4

    max_tool_calls: Annotated[
        int,
        Field(default=6, ge=1, le=MAX_AGENT_TOOL_CALLS, description="最多调用几次工具"),
    ] = 6


class AgentStepView(ContractResponse):
    """一步的审计记录：前端「Agent 跑了什么」直接渲染它。"""

    index: int = Field(ge=0, description="第几步（从 0 开始）")
    thought: str = Field(default="", description="模型给出的这一步的理由（仅供审计）")
    tool: str = Field(default="", description="调用的工具名；收尾步为空")
    label: str = Field(default="", description="结果的短标签，如「检索到 4 段」")
    error: str = Field(default="", description="这一步的问题（格式不符 / 工具不存在 / 预算触顶）")


class AgentAskResult(ContractResponse):
    """一次只读 Agent 运行的结果。"""

    answer: str = Field(default="", description="最终答案；预算触顶或中断时可能为空")
    citations: list[Citation] = Field(
        default_factory=list, description="引用；全部来自工具结果，片段与分数由服务端补齐"
    )
    done_reason: str = Field(
        description="stop（答完）/ length（预算触顶）/ cancelled（调用方中断）/ error / refused"
    )
    steps: list[AgentStepView] = Field(default_factory=list, description="逐步审计记录")
    tool_calls: int = Field(ge=0, description="实际调用工具的次数")
    interrupted_by: str = Field(default="", description="`caller` 或 `budget`，未中断时为空")
    usage_model: str | None = Field(default=None, description="实际使用的模型标识")
    latency_ms: int = Field(ge=0, description="端到端耗时")
