"""司职注册表：名字 → 司职。**唯一入口，未知名字绝不猜、绝不回退默认**。

为什么要写成「查不到就抛」而不是「查不到就用默认」：默认回退是这类骨架最贵的一个 bug ——
调用方把 `agent` 拼错成 `search` 时，系统会**照常跑完并返回一个看起来正常的答案**，
而账单与审计里记的是另一个岗位。用错误回退换来的那点可用性，代价是
「我明明选了核验员，为什么回答得像个检索助手」这种要翻日志才能查清的问题。

`DEFAULT_AGENT` 只用于**调用方没给名字**这一种情况（请求体里 `agent` 为空），
它等价于今天 `/agent/ask` 的形态 —— 骨架化的第一刀不许改变默认行为。
"""

from __future__ import annotations

from app.agents import answerer, searcher, verifier
from app.agents.profile import AgentProfile, UnknownAgentError

__all__ = [
    "AGENTS",
    "DEFAULT_AGENT",
    "EXECUTABLE_AGENTS",
    "MODEL_FREE_AGENTS",
    "SKELETON_AGENTS",
    "AgentProfile",
    "UnknownAgentError",
    "get_profile",
    "list_profiles",
]

#: 本批注册的三个司职。
#: 新增一个岗位 = 加一行 + 明确它的接线状态（`EXECUTABLE_AGENTS` 或 `SKELETON_AGENTS`）
#: + 若带工具则登记工具名（`app/api/v1/agent.py` 的 `PROFILE_TOOLS`）。
#: **不登记的工具名会在装配期直接报错**，不会静默少装。
AGENTS: dict[str, AgentProfile] = {
    answerer.PROFILE.name: answerer.PROFILE,
    searcher.PROFILE.name: searcher.PROFILE,
    verifier.PROFILE.name: verifier.PROFILE,
}

#: 缺省司职 = 今天的 `/agent/ask` 形态（ReAct 循环 + 只读检索工具）
DEFAULT_AGENT = searcher.PROFILE.name

#: 司职的**接线状态**：声明在注册表里不等于真的能跑，这一层把两者分开。
#:
#: - `EXECUTABLE_AGENTS`：请求点名它会真的执行；
#: - `MODEL_FREE_AGENTS`：它的工作是**确定性**的，一个模型都不调（`verifier` 就是这一类）。
#:   这一份不只是「省钱」的标注：「零模型调用」是它能在界面上随手点、也能进单测的前提，
#:   所以有断言专门盯着它（`tests/test_agents_verify.py`）；
#: - `SKELETON_AGENTS`：注册表里可见（前端能列出「有这个岗位」，说明它是**计划内**而不是漏了），
#:   但显式标记成**只有骨架** —— 点名它会得到一句可读的 400，而不是编一个答案。
#:   A2 之后它是空的；留着这个槽位是为了下次扩岗位时不必重新想一遍「该往哪儿放」。
#:
#: 三份名单都必须是 `AGENTS` 的**子集**，且 `EXECUTABLE_AGENTS` 里的每一个都要
#: **真的跑得起来**（带工具的：白名单里的工具能从工厂装出来；不带工具的：走生成或核验路径）。
#: 这些不变量有单测盯着（`tests/test_agents_registry.py`）——
#: 否则最容易发生的事是「新加了一个岗位，忘了接线」：它不会报错，
#: 只会表现得像另一个岗位（例如悄悄走了生成路径），而那种偏差最难查。
EXECUTABLE_AGENTS: frozenset[str] = frozenset(
    {answerer.PROFILE.name, DEFAULT_AGENT, verifier.PROFILE.name}
)

#: 一个模型都不调的司职（当前只有确定性核验）。
#: ⚠️ 它与「没有工具」**不是一回事**：`answerer` 也没有工具，但它要调模型生成。
MODEL_FREE_AGENTS: frozenset[str] = frozenset({verifier.PROFILE.name})

#: 只有骨架的司职（A2 之后为空：确定性核验已接线，语义核验留在下一轮）
SKELETON_AGENTS: frozenset[str] = frozenset()


def get_profile(name: str | None) -> AgentProfile:
    """按名字取司职。

    `name` 为空 → 取 `DEFAULT_AGENT`（等价于今天的形态）；
    名字未知 → 抛 `UnknownAgentError`，消息里带可选清单（端点据此翻成 422）。

    ⚠️ 这里**不做** `str.lower()` / `strip()` 之外的任何猜测：大小写写错也是写错，
    悄悄纠正会让「契约里的名字」与「实际生效的名字」出现两个版本。
    """
    if name is None:
        return AGENTS[DEFAULT_AGENT]
    key = str(name).strip().lower()
    if not key:
        return AGENTS[DEFAULT_AGENT]
    profile = AGENTS.get(key)
    if profile is None:
        raise UnknownAgentError(key, tuple(AGENTS))
    return profile


def list_profiles() -> list[AgentProfile]:
    """全部司职（按名字排序，给前端取清单用）。

    排序是**刻意的**：字典顺序会随注册顺序变化，前端清单跟着跳来跳去，
    而「可选值」这种东西的展示顺序不该由 import 顺序决定。
    """
    return [AGENTS[name] for name in sorted(AGENTS)]
