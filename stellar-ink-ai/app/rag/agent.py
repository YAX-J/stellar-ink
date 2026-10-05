"""只读 Agent：状态机 + 预算 + 中断（E2 核心）。

第一版 Agent 的形态刻意保守，三条硬边界：

1. **工具全部只读**。`AgentTool.read_only` 默认 True，`ToolBox` 在装配时就把非只读工具拒掉。
   红线 §7.4 是「第一版 Agent 工具全只读」——把它做成**一个能被测试断言的开关**，
   而不是一句注释，才不会在加第三个工具时被悄悄绕过。
2. **预算是硬上限，不是建议**。步数 / 工具调用次数 / 观察字符数三者任一触顶就**立即收尾**，
   并把 `done_reason` 标成 `length`（而不是假装正常答完）。没有预算的 Agent 最容易出的问题
   不是答错，而是「一直查下去」——那张账单要到月底才被发现。
3. **中断是状态，不是异常**。`AgentRun.interrupted` 记录「谁在哪一步停的」：
   调用方（用户关页面）或预算。异常只留给真正的错误。

为什么不用 LangGraph 之类的状态图框架：当前只有「想一步 → 调一个工具 → 再看」这一个循环，
引入图框架会带来一套需要同步的抽象与依赖，而**这里真正的难点是预算与中断的语义**，
不是流程图。真需要多分支状态机时再换，那时也应该先把本文件的语义固化下来。

模型的决策协议（JSON，便于解析也便于审计）：

```json
{"thought": "…", "tool": "search_posts", "arguments": {"question": "…"}}
{"thought": "…", "final": "答案", "citations": [{"postId": 1, "chunkIndex": 0}]}
```

`thought` 是**给审计看的**，不进最终答案；`citations` 只允许标 `postId`，
真实片段与分数一律由工具结果贴回去 —— 与问答同一条不变式：引用不能是模型的一面之词。
"""

from __future__ import annotations

import json
import re
import time
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Protocol

from app.core.trace import record_event
from app.providers.base import ChatModel
from app.providers.models import ChatMessage, MessageRole
from app.schemas.common import Citation, Role

#: 单次运行的默认预算。三个上限都要有：只限步数挡不住「一步里塞十个工具调用」，
#: 只限调用次数挡不住「一次观察把整篇文章灌回来」。
DEFAULT_MAX_STEPS = 4
DEFAULT_MAX_TOOL_CALLS = 6
DEFAULT_MAX_OBSERVATION_CHARS = 4000
#: 单个工具结果的字数上限：一条工具结果就能吃满整个预算，等于预算形同虚设
DEFAULT_TOOL_RESULT_CHARS = 1200


class StopReason(StrEnum):
    """收尾原因：与 `DoneReason` 同口径，便于两条链路共用前端渲染。"""

    STOP = "stop"
    LENGTH = "length"  # 预算触顶
    REFUSED = "refused"
    CANCELLED = "cancelled"
    ERROR = "error"


@dataclass(frozen=True, slots=True)
class ToolResult:
    """一次工具调用的结果。

    `summary` 是**进提示词**的那份（已按预算截断），`citations` 是给引用用的结构化线索。
    两者刻意分开：提示词里塞结构化 JSON 会浪费 token，而引用又必须有结构。
    """

    summary: str
    citations: list[Citation] = field(default_factory=list)
    #: 供审计/前端展示的短标签（如「检索到 5 篇」）
    label: str = ""


class AgentTool(Protocol):
    """一个只读工具。`name` 要能被模型拼对，所以用英文小写下划线。"""

    @property
    def name(self) -> str: ...

    @property
    def description(self) -> str: ...

    @property
    def read_only(self) -> bool: ...

    async def run(self, arguments: dict[str, Any]) -> ToolResult: ...


@dataclass(frozen=True, slots=True)
class ToolSpec:
    """一条工具的实现，用函数而不是类：工具本身没有状态，包一层类只是仪式感。

    E3-3 起它同时是**工具的标准描述**（MCP 的 `tools/list` 直接由它生成），因此除了给模型看的
    `description` / `arguments`（中文自然语言），还要有给程序看的 `input_schema`（JSON Schema）
    与三条治理字段：`required_role`（谁能调）、`timeout_ms`（多久算超时）、`idempotent`（能否重试）。
    两套描述放同一个对象里是刻意的：分开维护迟早会出现「提示词说能传 topK、schema 里没有」。
    """

    name: str
    description: str
    handler: Callable[[dict[str, Any]], Awaitable[ToolResult]]
    #: 只读标记。**默认 True**，要写数据必须显式改成 False（然后被 ToolBox 拒掉）
    read_only: bool = True
    #: 参数说明（拼进提示词，让模型知道该传什么）
    arguments: str = ""
    #: 参数的 JSON Schema（MCP 客户端据此构造调用）；空表示**不接受任何参数**
    input_schema: dict[str, Any] = field(default_factory=dict)
    #: 调用这条工具所需的最低角色（与 Java 三档一致：READER ⊂ AUTHOR ⊂ ADMIN）
    required_role: Role = Role.READER
    timeout_ms: int = 15_000
    #: 幂等：同样的参数重复调用不产生副作用。只读工具都应该是 True，客户端据此决定能否重试
    idempotent: bool = True

    def __post_init__(self) -> None:
        if self.timeout_ms <= 0:
            raise ValueError(f"工具超时必须为正：{self.name}={self.timeout_ms}")
        if not self.read_only and self.idempotent:
            # 写操作还声称幂等，等于鼓励客户端重试 —— 这正是「重复副作用」的来源
            raise ValueError(f"非只读工具不得声明幂等：{self.name}")

    def argument_names(self) -> set[str]:
        """schema 里声明的参数名；**空 schema 就是不接受任何参数**。"""
        properties = self.input_schema.get("properties")
        if not isinstance(properties, dict):
            return set()
        return {str(key) for key in properties}


class ToolBox:
    """工具集合。

    构造时就把非只读工具**拒之门外**：与其在运行期判断「这个工具能不能调」，
    不如让它根本进不来 —— 后者不可能被漏判。
    """

    def __init__(self, tools: Sequence[AgentTool | ToolSpec]) -> None:
        resolved: list[ToolSpec] = []
        for tool in tools:
            spec = (
                tool
                if isinstance(tool, ToolSpec)
                else ToolSpec(
                    name=tool.name,
                    description=tool.description,
                    handler=tool.run,
                    read_only=tool.read_only,
                )
            )
            if not spec.read_only:
                raise ValueError(f"第一版 Agent 只允许只读工具：{spec.name} 声明了 read_only=False")
            resolved.append(spec)
        self._specs = {spec.name: spec for spec in resolved}
        if not self._specs:
            raise ValueError("工具集为空：没有工具的 Agent 只会瞎猜，不如不要")

    @property
    def names(self) -> list[str]:
        return list(self._specs)

    def describe(self) -> str:
        return "\n".join(
            f"- {spec.name}（{spec.arguments or '无参数说明'}）：{spec.description}"
            for spec in self._specs.values()
        )

    def get(self, name: str) -> ToolSpec | None:
        return self._specs.get(name)


@dataclass(frozen=True, slots=True)
class AgentSettings:
    """预算与截断口径。改这些值会改变行为，所以它们必须显式、可打印。"""

    max_steps: int = DEFAULT_MAX_STEPS
    max_tool_calls: int = DEFAULT_MAX_TOOL_CALLS
    max_observation_chars: int = DEFAULT_MAX_OBSERVATION_CHARS
    tool_result_chars: int = DEFAULT_TOOL_RESULT_CHARS
    temperature: float | None = 0.2
    max_tokens: int | None = None

    def __post_init__(self) -> None:
        if self.max_steps < 1:
            raise ValueError("max_steps 必须为正：0 步的 Agent 不会调用任何工具")
        if self.max_tool_calls < 1:
            raise ValueError("max_tool_calls 必须为正")
        if self.max_observation_chars < 200:
            raise ValueError("max_observation_chars 太小：至少要放得下一条工具结果")
        if self.tool_result_chars < 100:
            raise ValueError("tool_result_chars 太小：截得太狠等于没有信息")


@dataclass(slots=True)
class AgentStep:
    """一步的审计记录（前端「Agent 跑了什么」直接渲染它）。"""

    index: int
    thought: str
    tool: str = ""
    arguments: dict[str, Any] = field(default_factory=dict)
    label: str = ""
    error: str = ""


@dataclass(slots=True)
class AgentRun:
    """一次运行的结果。`interrupted_by` 说明是调用方停的还是预算停的。"""

    answer: str
    citations: list[Citation]
    stop_reason: StopReason
    steps: list[AgentStep]
    tool_calls: int
    interrupted_by: str = ""
    usage_model: str | None = None
    latency_ms: int = 0

    @property
    def answered(self) -> bool:
        return self.stop_reason == StopReason.STOP

    @property
    def interrupted(self) -> bool:
        return bool(self.interrupted_by)


class _Interrupted(Exception):
    """内部信号：调用方要求中断（不对外暴露，避免把控制流当成错误处理）。"""


@dataclass(slots=True)
class Agent:
    """只读 Agent 的循环：想一步 → 调一个工具 → 再看 → 收尾。

    无状态（可并发复用），模型的决策能力由注入的 `ChatModel` 提供。

    A1 起它同时是**司职骨架的执行体**：提示词、工具集、预算都由 `app/agents` 的
    `AgentProfile` 声明，装配层（`app/api/v1/agent.py`）按司职把它拼出来。
    因此这个类**不认识「司职」这个概念** —— 它只拿到一份提示词、一个 `ToolBox` 与一份
    `AgentSettings`。这样做的收益是「各司职互不打扰」有了结构性保证：没有共享的
    全局状态、没有按名字分支的判断，每个请求都是一个新的 `Agent`。
    """

    chat: ChatModel
    tools: ToolBox
    settings: AgentSettings = field(default_factory=AgentSettings)
    #: 系统提示词。缺省就是 `SYSTEM_PROMPT`（= `searcher` 司职那份），
    #: 所以**老的直接实例化写法一行都不用改**（单测与 `searcher` 都依赖这一点）。
    system_prompt: str = ""
    #: 外部中断开关：返回 True 即停止（浏览器断开时由调用方置位）
    should_stop: Callable[[], bool] | None = None

    SYSTEM_PROMPT = (
        "你是「星笺」的只读助手。你可以调用工具去查站内文章与笔记，但**不能修改任何东西**。"
        "每一步只输出一个 JSON：要么调用一个工具，要么给出最终答案。不要输出别的文字。"
        '调用工具：{"thought":"为什么查","tool":"工具名","arguments":{...}}。'
        '给出答案：{"thought":"…","final":"答案",'
        '"citations":[{"postId":1,"kind":"post","chunkIndex":0}]}。'
        "答案只能依据工具返回的观察，不要用观察之外的知识；查不到就直说「没有找到依据」。"
        "引用只标 postId、kind 与 chunkIndex，片段与分数由系统补齐 —— 不要自己编片段。"
        "kind 取 post（文章）或 note（技术笔记）：文章 3 与笔记 3 是两篇不同的内容，"
        "标错了会指到另一篇去。"
    )

    def __post_init__(self) -> None:
        # 空字符串也回退到默认：调用方拿到 `profile.system_prompt` 直接传，
        # 而某个司职写空提示词是配置事故（模型会没有任何约束），不是「无提示词」这种合法状态
        if not self.system_prompt:
            self.system_prompt = Agent.SYSTEM_PROMPT

    async def run(self, question: str) -> AgentRun:
        text = str(question or "").strip()
        if not text:
            raise ValueError("问题不能为空")
        started = time.perf_counter()
        steps: list[AgentStep] = []
        observations: list[str] = []
        citations: list[Citation] = []
        tool_calls = 0
        observation_chars = 0
        # 默认收到「预算触顶」：只有真的给出答案才会改成 STOP
        stop_reason = StopReason.LENGTH

        try:
            for index in range(self.settings.max_steps):
                self._check_interrupt()
                decision = await self._decide(text, observations)
                step = AgentStep(index=index, thought=decision.thought)

                if decision.final is not None:
                    steps.append(step)
                    # 只保留「真实观察到过」的引用：模型凭空写的 postId 一律丢弃
                    citations = _verified_citations(decision.citations, citations)
                    return self._finish(
                        decision.final, citations, StopReason.STOP, steps, tool_calls, started
                    )

                if not decision.tool:
                    # 模型没按格式回答（或输出了既没 tool 也没 final 的 JSON）：
                    # **不中断整轮**，把问题说回去让它下一步改 —— 一次格式抖动不该毁掉整轮对话。
                    # 只有把预算耗光才会真正收尾（此时 stop_reason 已是 LENGTH）
                    step.error = "模型既没有选择工具，也没有给出答案（格式不符）"
                    steps.append(step)
                    observations.append(
                        "上一步的输出既没有 tool 也没有 final。"
                        '请严格输出 {"thought":…,"tool":…,"arguments":{…}} 或 '
                        '{"thought":…,"final":…}。'
                    )
                    continue

                if tool_calls >= self.settings.max_tool_calls:
                    step.error = f"工具调用已达上限 {self.settings.max_tool_calls}"
                    steps.append(step)
                    break

                spec = self.tools.get(decision.tool)
                if spec is None:
                    # 工具名拼错就告诉模型可用列表，让它下一步改 —— 这是模型最常见的失误
                    step.tool = decision.tool
                    step.error = f"没有这个工具：{decision.tool}"
                    steps.append(step)
                    observations.append(
                        f"工具 {decision.tool} 不存在。可用工具：{', '.join(self.tools.names)}"
                    )
                    continue

                step.tool = spec.name
                step.arguments = decision.arguments
                tool_started = time.perf_counter()
                result = await spec.handler(decision.arguments)
                step.label = result.label
                steps.append(step)
                citations = _merge_citations(citations, result.citations)
                record_event(
                    "tool",
                    tool=spec.name,
                    label=result.label,
                    citations=len(result.citations),
                    latencyMs=int((time.perf_counter() - tool_started) * 1000),
                    # 参数只记**键名**不记值：值里可能是作者的草稿或问题原文
                    argumentKeys=sorted(decision.arguments),
                )

                # 先算观察预算再计一次工具调用：**没拿到可进提示词的观察就不算花掉了调用额度**。
                # 反过来写会出现「第三次调用只得到一个空观察，却已经计进 tool_calls」——
                # 预算账目与实际用掉的钱对不上，正是这类 Agent 最容易被忽略的偏差
                remaining = self.settings.max_observation_chars - observation_chars
                snippet = _clip(result.summary, min(self.settings.tool_result_chars, remaining))
                if not snippet:
                    step.error = "观察预算已用尽"
                    break
                tool_calls += 1
                observations.append(f"[{spec.name}] {snippet}")
                observation_chars += len(snippet)

            # 循环走完（步数用尽 / 调用超限 / 观察超预算）都落到这里。
            # 退出前再查一次中断：调用方可能在最后一步之后按了停止，
            # 那种情况应当记为「用户取消」而不是「预算用尽」——两者的产品含义完全不同
            self._check_interrupt()
            # answer 为空但 citations 可能有：观察到的引用照常返回，
            # 前端可以显示「查到了这些，但没能在预算内收敛」——比什么都不给有用
            return self._finish("", citations, stop_reason, steps, tool_calls, started)
        except _Interrupted:
            return self._finish("", citations, StopReason.CANCELLED, steps, tool_calls, started)

    # --------------------------------------------------------------- 内部

    def _check_interrupt(self) -> None:
        """外部中断一律在**步与步之间**检查：调用工具的中途不会被打断，
        否则工具可能做到一半（读操作无所谓，但将来加写操作时这条边界很关键）。"""
        if self.should_stop is not None and self.should_stop():
            raise _Interrupted

    def _finish(
        self,
        answer: str,
        citations: list[Citation],
        stop_reason: StopReason,
        steps: list[AgentStep],
        tool_calls: int,
        started: float,
    ) -> AgentRun:
        model = getattr(self.chat, "MODEL_TAG", None)
        return AgentRun(
            answer=answer,
            citations=citations,
            stop_reason=stop_reason,
            steps=steps,
            tool_calls=tool_calls,
            interrupted_by="caller"
            if stop_reason == StopReason.CANCELLED
            else ("budget" if stop_reason == StopReason.LENGTH else ""),
            usage_model=model if isinstance(model, str) else None,
            latency_ms=max(0, int((time.perf_counter() - started) * 1000)),
        )

    async def _decide(self, question: str, observations: list[str]) -> _Decision:
        messages = [
            ChatMessage(role=MessageRole.SYSTEM, content=self.system_prompt),
            ChatMessage(
                role=MessageRole.USER, content=_user_prompt(question, self.tools, observations)
            ),
        ]
        response = await self.chat.chat(
            messages, temperature=self.settings.temperature, max_tokens=self.settings.max_tokens
        )
        return _parse_decision(response.text)


@dataclass(frozen=True, slots=True)
class _Claim:
    """模型声称引用的一段（**未经核实**）：只有文档标识与段落号，没有片段。"""

    post_id: int
    chunk_index: int = 0
    #: 内容种类（`post` / `note`）。缺省按 `post`：老提示词与模型习惯里只有 postId，
    #: 而「文章 3 与笔记 3」是两个文档 —— 少了它，核实环节会把两者当成同一篇。
    kind: str = "post"


@dataclass(frozen=True, slots=True)
class _Decision:
    """模型的一步决策。`final` 非空表示它选择收尾。"""

    thought: str
    tool: str = ""
    arguments: dict[str, Any] = field(default_factory=dict)
    final: str | None = None
    citations: list[_Claim] = field(default_factory=list)


def _parse_decision(raw: str) -> _Decision:
    """解析模型的一步决策。

    **解析失败不抛异常**：那会让一次格式抖动毁掉整轮对话。返回一个「没有工具也没有答案」
    的决策，循环会把它记成一步错误并继续 —— 模型下一步通常能改对。
    """
    text = (raw or "").strip()
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return _Decision(thought="", tool="", final=None)
    try:
        payload = json.loads(text[start : end + 1])
    except ValueError:
        return _Decision(thought="", tool="", final=None)
    if not isinstance(payload, dict):
        return _Decision(thought="", tool="", final=None)

    thought = str(payload.get("thought") or "")
    final = payload.get("final")
    if isinstance(final, str) and final.strip():
        return _Decision(thought=thought, final=final.strip(), citations=_parse_citations(payload))

    tool = payload.get("tool")
    arguments = payload.get("arguments")
    if not isinstance(tool, str) or not tool.strip():
        # 既没有 final 也没有 tool：这不是一个可执行的决策。
        # **不要把它当成「调用了空名字的工具」**——那会让循环多走一步、日志里多一条假记录
        return _Decision(thought=thought, tool="", final=None)
    return _Decision(
        thought=thought,
        tool=tool.strip(),
        arguments={str(k): v for k, v in arguments.items()} if isinstance(arguments, dict) else {},
    )


def _parse_citations(payload: dict[str, Any]) -> list[_Claim]:
    """从模型输出里取引用**线索**。

    只接受 `postId`（必需）、`kind`（可选，`post` / `note`）与 `chunkIndex`（可选），
    而且要**先当线索而不是引用**：`Citation` 契约要求 snippet 非空（引用必须能定位回原文），
    而模型并不提供片段 —— 片段与分数一律由工具结果补齐。硬要用 Citation 装线索会直接触发
    校验错误（第一版就是这么写的，pydantic 立刻拒绝了空 snippet），
    这也从侧面说明「引用不能由模型给」这条约束在类型层面就站得住。
    """
    raw = payload.get("citations")
    if not isinstance(raw, list):
        return []
    claims: list[_Claim] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        post_id = item.get("postId")
        if not isinstance(post_id, int) or post_id <= 0:
            continue
        raw_index = item.get("chunkIndex")
        kind = item.get("kind")
        claims.append(
            _Claim(
                post_id=post_id,
                chunk_index=raw_index if isinstance(raw_index, int) and raw_index >= 0 else 0,
                # 白名单之外的 kind 按 post：模型可能写出别的词，而「猜一个种类」
                # 比「按文章处理」更容易指错文档
                kind=kind if kind in {"post", "note"} else "post",
            )
        )
    return claims


def citation_key(citation: Citation) -> tuple[str, int, int]:
    """引用的**文档标识**：`(kind, postId, chunkIndex)`。

    单独开出来（而不是留在 `_verified_citations` 里）的理由：核验器
    （`app/agents/verifier.py`）要按同一把钥匙回查原文。
    两处各写一遍比较键，现象是「同一个引用在 Agent 里算被观察到、在核验器里算没被观察到」——
    而两边都「有代码有注释」。文档标识是 `kind + post_id`：文章 3 与笔记 3 是两篇。
    """
    return (str(citation.kind), citation.post_id, citation.chunk_index)


#: 答案里的引用编号：`[1]`、`[12]`。**只认方括号里的纯数字** ——
#: 「[见上文]」不算；「[1,2]」也不算（那是模型的另一种写法，本轮不猜它的语义：
#: 猜错会把「没标编号」说成「标了」，而这两种结论的处置完全不同）。
_CITE_MARK = re.compile(r"\[(\d+)\]")


def parse_citation_marks(answer: str) -> list[int]:
    """取出答案里标出的引用编号（`[n]`），**按出现顺序去重**。

    为什么单独成函数：问答与 Agent 的提示词都要求「用 [1] [2] 标注来源」，
    而核验（A2）要判「编号有没有越界」。谁都能写一行正则，但**口径必须只有一份**：
    同一段答案在两处被解析出不同的编号集合，就会出现「前端说有引用、核验说没标」。
    """
    seen: set[int] = set()
    marks: list[int] = []
    for raw in _CITE_MARK.findall(str(answer or "")):
        number = int(raw)
        if number in seen:
            continue
        seen.add(number)
        marks.append(number)
    return marks


def verified_citations(claimed: Sequence[_Claim], observed: Sequence[Citation]) -> list[Citation]:
    """只保留**工具真的返回过**的引用，并按观察顺序给出完整片段与分数。

    这一步是引用可信的关键：模型可能记错 postId、也可能把别的文章编进来。
    声称但没观察到的一律丢掉 —— 宁可少一条引用，也不要给读者一个指向错误内容的链接。

    核实按**文档标识** `(kind, postId, chunkIndex)` 做（见 `citation_key`）：只用 `postId`
    的话，「文章 3」与「笔记 3」会互相通过核实，而它们是完全不同的两篇。

    模型一条都没标对时，退化成「把观察到的引用原样带上」：答案是依据它们写的，
    一条引用都不给反而让读者无法核对。

    ⚠️ 这是**共享口径**：核验器（A2）按同一条规则判「引用的片段有没有被观察到」，
    所以实现只有这一份，`_verified_citations` 只是它的历史别名。
    """
    allowed: dict[tuple[str, int, int], Citation] = {
        citation_key(item): item for item in observed
    }
    kept: list[Citation] = []
    for claim in claimed:
        real = allowed.get((claim.kind, claim.post_id, claim.chunk_index))
        if real is not None and real not in kept:
            kept.append(real)
    return kept or _dedupe(observed)


def _verified_citations(claimed: Sequence[_Claim], observed: Sequence[Citation]) -> list[Citation]:
    """历史入口（口径在 `verified_citations`）：保留是因为循环里读起来像「做了一次核实」。"""
    return verified_citations(claimed, observed)


def _dedupe(citations: Sequence[Citation]) -> list[Citation]:
    """按身份去重：`Citation` 是 pydantic 模型（不可哈希），而且同一篇的不同片段应当都保留。"""
    seen: set[int] = set()
    unique: list[Citation] = []
    for citation in citations:
        marker = id(citation)
        if marker in seen:
            continue
        seen.add(marker)
        unique.append(citation)
    return unique


def _merge_citations(current: list[Citation], new: Sequence[Citation]) -> list[Citation]:
    merged = list(current)
    for citation in new:
        if citation not in merged:
            merged.append(citation)
    return merged


def _clip(text: str, limit: int) -> str:
    """截断到 limit 字：优先保留开头（观察的开头通常是结论或最相关的片段）。"""
    body = str(text or "").strip()
    if limit <= 0:
        return ""
    if len(body) <= limit:
        return body
    return body[: max(0, limit - 1)].rstrip() + "…"


def _user_prompt(question: str, tools: ToolBox, observations: list[str]) -> str:
    lines = [f"问题：{question}", "", "可用工具：", tools.describe(), ""]
    if observations:
        lines.append("已有的观察：")
        lines.extend(observations)
        lines.append("")
    lines.append("请输出下一步的 JSON（调用工具，或给出最终答案）。")
    return "\n".join(lines)
