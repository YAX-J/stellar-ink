"""只读 Agent 的契约（E2；A1 起按**司职**可配）。

Agent 的输出**不是答案本身**，而是「答案 + 它做过什么 + 谁做的」：
`steps` 里每一条都是一次工具调用或一次格式错误，前端据此展示「它查了哪些东西」；
`agent` 回显生效的司职，前端据此显示「谁答的」（以及让审计能对上账）。
只给答案的 Agent 在生产里是不可运维的 —— 出了错没人知道它查了什么、在哪一步跑偏。

四条与实现一致的约定：
- `agent` 缺省为空 → 服务端取 `DEFAULT_AGENT`（= 今天的形态）；
  名字未知给 **422** 并在消息里列出可选值，**不猜也不回退默认**；
- `maxSteps` / `maxToolCalls` 缺省为空 → 用**司职声明的预算**；给了也只能收紧
  （与司职上限取 min），所以字段上的 `le` 是「契约的绝对上限」而不是生效值；
- `stopReason=length` 表示**预算触顶**，此时 `answer` 可能为空但 `citations` 可能有 ——
  前端要显示「查到这些，但没能在预算内收敛」，而不是一片空白；
- 引用只来自工具结果，`citations` 里的片段与分数都是服务端贴回去的。
"""

from typing import Annotated

from pydantic import Field

from app.agents.registry import DEFAULT_AGENT
from app.schemas.base import ContractRequest, ContractResponse
from app.schemas.common import MAX_QUESTION_LENGTH, Citation

#: 契约层的**绝对**上限；真正生效的是司职声明的预算（≤ 这两个数）。
#: 与 `app/rag/agent.py` 的默认预算一致口径：对外允许收紧，不允许放宽
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

    agent: Annotated[
        str | None,
        Field(
            default=None,
            description=(
                "司职名（answerer / searcher / verifier）；留空用服务端缺省，未知名字返回 422"
            ),
        ),
    ] = None

    max_steps: Annotated[
        int | None,
        Field(
            default=None,
            ge=1,
            le=MAX_AGENT_STEPS,
            description="最多推理几步（含收尾那一步）；留空用该司职的预算，给了也只能收紧",
        ),
    ] = None

    max_tool_calls: Annotated[
        int | None,
        Field(
            default=None,
            ge=1,
            le=MAX_AGENT_TOOL_CALLS,
            description="最多调用几次工具；留空用该司职的预算，给了也只能收紧",
        ),
    ] = None


class AgentStepView(ContractResponse):
    """一步的审计记录：前端「Agent 跑了什么」直接渲染它。"""

    index: int = Field(ge=0, description="第几步（从 0 开始）")
    thought: str = Field(default="", description="模型给出的这一步的理由（仅供审计）")
    tool: str = Field(default="", description="调用的工具名；收尾步为空")
    label: str = Field(default="", description="结果的短标签，如「检索到 4 段」")
    error: str = Field(default="", description="这一步的问题（格式不符 / 工具不存在 / 预算触顶）")


class AgentProfileView(ContractResponse):
    """一个司职的**元数据**（不含提示词：它属于 `/prompts` 的出口）。"""

    name: str = Field(min_length=1, description="司职名（请求体 `agent` 用的就是它）")
    title: str = Field(default="", description="给人看的中文名")
    tool_names: list[str] = Field(default_factory=list, description="允许装配的只读工具名")
    scene: str = Field(default="agent", description="调用账里的场景名（司职之间分开算钱）")
    max_steps: int = Field(ge=1, description="该司职的步数上限（客户端只能收紧到这个值以下）")
    max_tool_calls: int = Field(ge=1, description="该司职的工具调用次数上限")


class AgentProfileList(ContractResponse):
    """``GET /agent/profiles`` 的响应体：给前端取清单用。"""

    default_agent: str = Field(min_length=1, description="不传 `agent` 时生效的司职")
    agents: list[AgentProfileView] = Field(default_factory=list, description="全部司职")


#: 核验请求里答案的长度上限。答案由本服务产出，但**不能假设它一定有界**：
#: 挡住无界入参是所有内部端点都该做的事，而这里多一行就够了。
MAX_ANSWER_LENGTH = 20_000


class AgentVerifyRequest(ContractRequest):
    """``POST /agent/verify`` 的请求体（A2 确定性引用核验）。

    只收「答案 + 引用」：核验要用的**原文**由服务端自己从语料取（`app/rag/corpus.py`），
    **不接受调用方传进来** —— 证据必须是服务端认的那一份；否则「片段与原文对不上」
    就退化成「调用方说原文是什么就是什么」。
    """

    answer: str = Field(
        default="",
        max_length=MAX_ANSWER_LENGTH,
        description=f"待核验的答案（不超过 {MAX_ANSWER_LENGTH} 字）",
    )

    citations: list[Citation] = Field(
        default_factory=list, description="答案附带的引用（就是答案后面那份清单）"
    )


class AgentVerifyProblemView(ContractResponse):
    """一条核验问题：`kind` 给程序，`message` 给人。"""

    kind: str = Field(min_length=1, description="问题分类（outOfRange / uncited / …）")
    message: str = Field(min_length=1, description="可读说明（可直接展示给用户）")


class AgentVerifyResult(ContractResponse):
    """核验报告。

    ⚠️ `verdict=ok` 只表示**没查出问题**，不等于「这段答案是对的」：
    语义核验（论断有没有依据）还没做 —— 所以 `checked` 必须一起显示
    （「已核对 N 条引用」，而不是「答案已核实」）。
    """

    verdict: str = Field(description="ok（没查出问题）/ warn（查出问题）")
    checked: int = Field(ge=0, description="回查到原文的引用条数（`ok` 的可信度分母）")
    evidence_available: bool = Field(
        default=True,
        description="这次有没有原文可查；为 false 时 `checked` 必然为 0（别说成「引用没问题」）",
    )
    cited_indexes: list[int] = Field(
        default_factory=list, description="答案里标出的引用编号（按出现顺序去重）"
    )
    out_of_range: list[int] = Field(default_factory=list, description="其中越界的编号")
    uncited: bool = Field(default=False, description="有引用却一个编号都没标")
    problems: list[AgentVerifyProblemView] = Field(
        default_factory=list, description="可读的问题清单；为空即 `verdict=ok`"
    )


class AgentAskResult(ContractResponse):
    """一次只读 Agent 运行的结果。"""

    agent: str = Field(
        # 同一个缺省值只能有一处定义：硬编码 "searcher" 会在换缺省司职时留下一个
        # 「契约说 searcher、实际跑 answerer」的坑
        default=DEFAULT_AGENT,
        description="本次实际生效的司职（回显；前端显示「谁答的」）",
    )
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
