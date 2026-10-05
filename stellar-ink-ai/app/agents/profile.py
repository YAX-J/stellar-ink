"""司职（role/profile）描述：一个 Agent 的「是谁、能干什么、花多少」。

为什么要有这一层：Agent 不是一个动词而是一个**岗位**。今天的 `/agent/ask` 只有一种形态
（只读、带检索工具、多步），一旦要加「只生成、不查」「只核验、不答」，这些差异
（提示词 / 工具集 / 预算）就会散在端点装配代码里，靠 `if name == …` 分支维护。
收进一个 frozen dataclass 之后，司职是**数据**：可枚举、可打印、可被测试逐项断言，
新增一个岗位不需要改循环本身。

三条刻意的口径：

1. **`tool_names` 是白名单，不是过滤器**：装配层按它挑工具，声明了但没装上的工具
   **直接报错**而不是静默忽略 —— 「提示词里说能查 X、实际没装 X」是这类骨架最典型的坏味道。
2. **写工具仍然装不进来**：白名单只决定「装哪几个只读工具」，
   `ToolBox.__init__` 依旧拒绝 `read_only=False`（红线 §7.4）。两道门是叠加的，不是二选一。
3. **`settings` 是上限不是建议**：外部请求只能收紧（取 min），不能放宽 ——
   司职自己声明它最多值多少步，调用方说不了算。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from app.rag.agent import AgentSettings


class UnknownAgentError(LookupError):
    """未知司职名。

    刻意**不**继承 `ValueError`：端点上 `ValueError` 已经表示「模型没按格式回答 / 输入不合法」，
    混用一个类型会让「叫错了岗位名」被翻成一句与真实原因无关的提示。也不继承
    `KeyError`：那个默认 `repr` 会把消息再套一层引号。
    """

    def __init__(self, name: str, available: tuple[str, ...]) -> None:
        self.name = name
        self.available = available
        # 消息里必须列出可选值：只说「不认识 searcherr」等于让人去翻代码
        options = "、".join(available) if available else "（未注册任何司职）"
        super().__init__(f"未知的 Agent 司职：{name or '(空)'}。可选司职：{options}")


@dataclass(frozen=True, slots=True)
class AgentProfile:
    """一个司职的完整描述。

    `name` 是对外契约里回显的那个键（`AgentAskResult.agent`），因此它必须是**稳定标识**：
    改名字等于改契约，`title` 才是给人看的中文名。
    """

    name: str
    #: 给人看的中文名（`name` 是契约里的稳定键，改名字等于改契约；改中文名不是）
    title: str
    system_prompt: str
    #: 允许装配的**只读**工具名（顺序即提示词里的顺序）。空元组 = 这个司职没有工具
    tool_names: tuple[str, ...] = ()
    settings: AgentSettings = field(default_factory=AgentSettings)
    #: 调用账（`AiCallScene`）里的场景名：司职之间要能分开算钱
    scene: str = "agent"
