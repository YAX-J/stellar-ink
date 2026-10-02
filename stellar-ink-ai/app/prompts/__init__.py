"""提示词注册表的装配（M8）。

**模板留在各自的模块里，注册表只做索引** —— 这样反过来做（把模板搬进注册表）
会让每个用到提示词的模块都依赖注册表，而注册表又得依赖它们来拿模板，绕成一圈。

装配是**惰性**的（第一次用时才建）：`build_registry()` 需要 import 各个 rag 模块，
如果在 import 期就建，就等于给「谁先加载」加了一条隐形依赖。
"""

from __future__ import annotations

from app.prompts.registry import (
    PromptError,
    PromptRegistry,
    PromptSpec,
    self_check,
)

__all__ = [
    "PromptError",
    "PromptRegistry",
    "PromptSpec",
    "build_registry",
    "registry",
    "registry_problems",
    "self_check",
]

_REGISTRY: PromptRegistry | None = None


def build_registry() -> PromptRegistry:
    """把现有提示词收进注册表。

    ⚠️ `evaluation` 字段**如实留白**：没有被评测过的提示词就写 `None`，
    界面显示「尚未评测」。编一个「效果良好」比留白危险得多 —— 后者只是信息少，
    前者会让人以为「这一版已经验过了」。
    """
    from app.rag.agent import Agent
    from app.rag.memory import PROMPT as MEMORY_PROMPT_TEMPLATE
    from app.rag.qa import MEMORY_PROMPT as QA_MEMORY_PROMPT
    from app.rag.qa import SYSTEM_PROMPT as QA_SYSTEM
    from app.rag.wiki import PROMPT as WIKI_PROMPT_TEMPLATE
    from app.rag.writing import SYSTEM_PROMPT as WRITING_SYSTEM

    return PromptRegistry(
        [
            PromptSpec(
                name="qa.system",
                version=1,
                interpolated=False,
                template=QA_SYSTEM,
                variables=(),
                description="星海问答的系统提示词：只依据摘录作答、逐条编号、不足则明说",
                evaluation=(
                    "黄金集 30 题（Recall@1 0.8333 / NDCG@5 0.9485，见 docs/ai/status.md §3）"
                ),
            ),
            PromptSpec(
                name="qa.memory",
                version=1,
                interpolated=False,
                template=QA_MEMORY_PROMPT,
                variables=(),
                description="长期记忆段（M9）：明确记忆不是文章内容、不得编号引用、冲突以摘录为准",
                evaluation=None,
            ),
            PromptSpec(
                name="wiki.claims",
                version=1,
                template=WIKI_PROMPT_TEMPLATE,
                variables=("title", "chunks", "max_per_chunk", "example", "kinds"),
                description="LLM Wiki 主张+实体抽取：每条主张必须带**能在原文里找到**的引用",
                output_schema={"claims": "数组，每条含 text/quote/headingPath", "entities": "数组"},
                evaluation=(
                    "离线 fixture 覆盖了「引用编造被丢弃」与「实体只出现在被丢弃主张里」两类"
                ),
            ),
            PromptSpec(
                name="memory.candidates",
                version=1,
                template=MEMORY_PROMPT_TEMPLATE,
                variables=("types", "conversation", "example"),
                description="作者记忆候选抽取（M9）：每条候选必须给出**逐字来自对话**的出处",
                output_schema={"candidates": "数组，每条含 type/content/quote/confidence"},
                evaluation=None,
            ),
            PromptSpec(
                name="writing.suggest",
                version=1,
                interpolated=False,
                template=WRITING_SYSTEM,
                variables=(),
                description="执笔页 Copilot：只给候选、不替作者决定，且不自动应用",
                evaluation=None,
            ),
            PromptSpec(
                name="agent.system",
                version=1,
                interpolated=False,
                template=Agent.SYSTEM_PROMPT,
                variables=(),
                description=(
                    "只读 Agent 的决策协议：{thought,tool,arguments} 或 {thought,final,citations}"
                ),
                roles=("chat", "reasoning"),
                output_schema={"thought": "字符串", "tool": "工具名", "final": "结论"},
                evaluation=None,
            ),
        ]
    )


def registry() -> PromptRegistry:
    """进程内单例（惰性装配）。"""
    global _REGISTRY
    if _REGISTRY is None:
        _REGISTRY = build_registry()
    return _REGISTRY


def registry_problems() -> list[str]:
    """启动/请求期自检：声明的变量与模板里实际用到的必须一致。"""
    return self_check(registry())
