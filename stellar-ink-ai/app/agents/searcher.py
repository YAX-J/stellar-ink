"""`searcher` 司职：今天 `/agent/ask` 的形态（ReAct 循环 + 只读工具）。

提示词**原样搬过来**（E2 精简版 Agent），一个字都没改 —— 骨架化的第一刀要求
「默认行为与现在完全一致」，而提示词是行为的一部分：改了措辞就等于换了一个 Agent，
届时的差异会被误读成"重构带来的退化"。

这个模块只声明「我是谁、我能用哪些工具、我最少花多少钱」，循环本身仍然在
`app/rag/agent.py`（那里可单测、与司职无关）。
"""

from __future__ import annotations

from app.agents.profile import AgentProfile
from app.rag.agent import (
    DEFAULT_MAX_OBSERVATION_CHARS,
    DEFAULT_MAX_STEPS,
    DEFAULT_MAX_TOOL_CALLS,
    AgentSettings,
)

#: 与 `Agent.SYSTEM_PROMPT` 逐字相同（单一来源：那边是这条常量的别名）
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

#: 今天默认的预算（4 步 / 6 次调用 / 4000 观察字符）。
#: 三个都显式写出来而不是 `AgentSettings()`：外部请求只能**收紧到**这些值，
#: 所以它们是对外承诺的一部分，改动要当成契约改动看。
SETTINGS = AgentSettings(
    max_steps=DEFAULT_MAX_STEPS,
    max_tool_calls=DEFAULT_MAX_TOOL_CALLS,
    max_observation_chars=DEFAULT_MAX_OBSERVATION_CHARS,
)

PROFILE = AgentProfile(
    name="searcher",
    title="检索助手",
    system_prompt=SYSTEM_PROMPT,
    tool_names=("search_posts",),
    settings=SETTINGS,
    scene="agent",
)
