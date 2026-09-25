"""只读 Agent 的测试：预算、中断、引用可信、工具只读。

这些断言的重点不是「Agent 会不会答题」（那取决于模型），而是**它不会做什么**：
不会越过预算一直查、不会引用没观察过的东西、不会拿到写工具、不会把中断当异常抛出去。
一个 Agent 的可用性几乎完全由这几条边界决定。
"""

from __future__ import annotations

from typing import Any

import pytest

from app.providers.fake import FakeProvider
from app.providers.models import ChatMessage, ChatResponse, TokenUsage
from app.rag.agent import (
    Agent,
    AgentSettings,
    StopReason,
    ToolBox,
    ToolResult,
    ToolSpec,
)
from app.rag.agent_tools import AuthorStyleTool, SearchPostsTool, read_only_tools
from app.rag.pipeline import IndexedChunk, RetrievalConfig, RetrievalPipeline
from app.schemas.common import Citation

QUESTION = "一年写十八万字的方法是什么？"


class ScriptedChat:
    """按脚本回答的模型桩：让「Agent 的循环」可以被精确断言。"""

    MODEL_TAG = "scripted"

    def __init__(self, replies: list[str]) -> None:
        self._replies = list(replies)
        self.calls: list[list[ChatMessage]] = []

    async def chat(
        self,
        messages: list[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> ChatResponse:
        self.calls.append(list(messages))
        reply = self._replies.pop(0) if self._replies else '{"thought":"没了","final":"结束"}'
        return ChatResponse(text=reply, usage=TokenUsage(model=self.MODEL_TAG))


def tool(
    name: str,
    *,
    summary: str = "观察",
    citations: list[Citation] | None = None,
    read_only: bool = True,
) -> ToolSpec:
    async def handler(arguments: dict[str, Any]) -> ToolResult:
        return ToolResult(summary=summary, citations=citations or [], label=f"{name} 跑过")

    return ToolSpec(name=name, description=f"{name} 的描述", handler=handler, read_only=read_only)


def build_pipeline(refuse: bool = False) -> RetrievalPipeline:
    corpus = [
        IndexedChunk(
            chunk_id="c1",
            post_id=1,
            title="写作的复利",
            text="写作的复利\n一年写十八万字靠的是每天五百字，不是等灵感。",
            payload={"text": "一年写十八万字靠的是每天五百字，不是等灵感。", "chunkIndex": 0},
        ),
    ]
    config = RetrievalConfig(enable_dense=False, min_score=10_000.0 if refuse else 0.0)
    return RetrievalPipeline(corpus=corpus, config=config, embedder=FakeProvider())


# --------------------------------------------------------------- 工具边界


def test_toolbox_refuses_non_read_only_tools() -> None:
    """第一版 Agent 全只读：写工具**装不进来**，而不是运行期再判断。"""
    with pytest.raises(ValueError, match="只读"):
        ToolBox([tool("delete_post", read_only=False)])


def test_toolbox_refuses_to_be_empty() -> None:
    with pytest.raises(ValueError, match="空"):
        ToolBox([])


def test_toolbox_describes_tools_for_the_prompt() -> None:
    box = ToolBox([tool("search_posts")])

    described = box.describe()

    assert "search_posts" in described and "描述" in described
    assert box.get("search_posts") is not None
    assert box.get("nope") is None


def test_settings_reject_unusable_budgets() -> None:
    with pytest.raises(ValueError, match="max_steps"):
        AgentSettings(max_steps=0)
    with pytest.raises(ValueError, match="max_observation_chars"):
        AgentSettings(max_observation_chars=10)


# --------------------------------------------------------------- 循环与预算


async def test_agent_calls_a_tool_then_answers() -> None:
    chat = ScriptedChat(
        [
            '{"thought":"先查","tool":"search_posts","arguments":{"question":"写多少"}}',
            '{"thought":"够了","final":"每天五百字。","citations":[{"postId":1,"chunkIndex":0}]}',
        ]
    )
    citation = Citation(post_id=1, title="写作的复利", chunk_index=0, snippet="每天五百字。")
    agent = Agent(
        chat=chat,
        tools=ToolBox([tool("search_posts", summary="每天五百字。", citations=[citation])]),
    )

    run = await agent.run(QUESTION)

    assert run.stop_reason == StopReason.STOP
    assert run.answered
    assert run.answer == "每天五百字。"
    assert run.tool_calls == 1
    assert [step.tool for step in run.steps] == ["search_posts", ""]
    assert run.citations == [citation], "引用必须来自工具结果（带片段与分数）"


async def test_agent_stops_when_the_step_budget_runs_out() -> None:
    always_tool = '{"thought":"再查","tool":"search_posts","arguments":{}}'
    chat = ScriptedChat([always_tool] * 10)
    agent = Agent(
        chat=chat,
        tools=ToolBox([tool("search_posts")]),
        settings=AgentSettings(max_steps=3),
    )

    run = await agent.run(QUESTION)

    assert run.stop_reason == StopReason.LENGTH, "预算用尽要如实标成长度上限，不能假装答完了"
    assert not run.answered
    assert run.interrupted_by == "budget"
    assert len(run.steps) == 3
    assert len(chat.calls) == 3, "步数上限是硬上限：不能多问一次模型"


async def test_agent_stops_when_tool_calls_run_out() -> None:
    """「一步里塞很多工具调用」也要被挡住：只限步数挡不住这种花法。"""
    chat = ScriptedChat([f'{{"thought":"查","tool":"t{i}","arguments":{{}}}}' for i in range(10)])
    agent = Agent(
        chat=chat,
        tools=ToolBox([tool(f"t{i}") for i in range(10)]),
        settings=AgentSettings(max_steps=10, max_tool_calls=2),
    )

    run = await agent.run(QUESTION)

    assert run.tool_calls == 2
    assert run.stop_reason == StopReason.LENGTH
    assert any("上限" in step.error for step in run.steps)


async def test_observation_budget_clips_and_stops() -> None:
    chat = ScriptedChat([f'{{"thought":"查","tool":"t{i}","arguments":{{}}}}' for i in range(10)])
    long_summary = "观察" * 500
    agent = Agent(
        chat=chat,
        tools=ToolBox([tool(f"t{i}", summary=long_summary) for i in range(10)]),
        settings=AgentSettings(
            max_steps=10, max_tool_calls=10, max_observation_chars=600, tool_result_chars=400
        ),
    )

    run = await agent.run(QUESTION)

    assert run.stop_reason == StopReason.LENGTH
    # 两次观察就吃满 600 字预算（每次被 tool_result_chars 截到 400）
    assert run.tool_calls == 2, f"观察预算没生效：跑了 {run.tool_calls} 次"
    assert any(step.error for step in run.steps[-1:]), "触顶那一步要留下原因"


async def test_single_tool_result_cannot_exhaust_the_prompt() -> None:
    """一条工具结果的字数上限独立于总预算：否则第一次观察就把预算吃光。"""
    chat = ScriptedChat(
        ['{"thought":"查","tool":"t","arguments":{}}', '{"thought":"完了","final":"好"}']
    )
    agent = Agent(
        chat=chat,
        tools=ToolBox([tool("t", summary="很长的观察" * 1000)]),
        settings=AgentSettings(tool_result_chars=200, max_observation_chars=4000),
    )

    run = await agent.run(QUESTION)

    first_observation = run.steps[0]
    assert first_observation.label, "工具结果应当留下可展示的标签"
    assert run.stop_reason == StopReason.STOP, "截断不该导致跑不完"


# --------------------------------------------------------------- 引用可信


async def test_citations_must_be_observed() -> None:
    """模型编的 postId 一律丢掉：引用指向错文章比没有引用更糟。"""
    chat = ScriptedChat(
        [
            '{"thought":"查","tool":"search_posts","arguments":{}}',
            '{"thought":"答","final":"就这样","citations":[{"postId":999,"chunkIndex":0}]}',
        ]
    )
    real = Citation(post_id=1, title="真文章", chunk_index=0, snippet="真片段")
    agent = Agent(chat=chat, tools=ToolBox([tool("search_posts", citations=[real])]))

    run = await agent.run(QUESTION)

    assert 999 not in [item.post_id for item in run.citations]
    assert run.citations == [real], "模型没标对时，用真实观察到的那条（答案是依据它写的）"


async def test_agent_falls_back_to_observed_citations_when_model_omits_them() -> None:
    chat = ScriptedChat(
        [
            '{"thought":"查","tool":"search_posts","arguments":{}}',
            '{"thought":"答","final":"就这样"}',
        ]
    )
    real = Citation(post_id=1, title="真文章", chunk_index=0, snippet="真片段")
    agent = Agent(chat=chat, tools=ToolBox([tool("search_posts", citations=[real])]))

    run = await agent.run(QUESTION)

    assert run.citations == [real]


# --------------------------------------------------------------- 中断与坏输出


async def test_caller_can_interrupt_between_steps() -> None:
    chat = ScriptedChat(['{"thought":"查","tool":"search_posts","arguments":{}}'] * 5)
    checks = {"count": 0}

    def should_stop() -> bool:
        checks["count"] += 1
        return checks["count"] > 1  # 第一步之后停

    agent = Agent(
        chat=chat,
        tools=ToolBox([tool("search_posts")]),
        settings=AgentSettings(max_steps=5),
        should_stop=should_stop,
    )

    run = await agent.run(QUESTION)

    assert run.stop_reason == StopReason.CANCELLED
    assert run.interrupted_by == "caller"
    assert run.tool_calls == 1, "中断后不该再调工具"
    assert len(chat.calls) == 1


async def test_malformed_decision_does_not_crash_the_run() -> None:
    """一次格式抖动不该毁掉整轮：记一步错误，让模型下一步改。"""
    chat = ScriptedChat(
        [
            "模型今天不想输出 JSON",
            '{"thought":"补上","tool":"search_posts","arguments":{}}',
            '{"thought":"答","final":"好了"}',
        ]
    )
    agent = Agent(chat=chat, tools=ToolBox([tool("search_posts")]))

    run = await agent.run(QUESTION)

    assert run.stop_reason == StopReason.STOP
    assert run.answer == "好了"
    assert run.steps[0].error, "坏输出要留下痕迹，不能静默跳过"


async def test_unknown_tool_name_is_reported_back_to_the_model() -> None:
    chat = ScriptedChat(
        [
            '{"thought":"查","tool":"search_post","arguments":{}}',
            '{"thought":"改名","final":"不查了"}',
        ]
    )
    agent = Agent(chat=chat, tools=ToolBox([tool("search_posts")]))

    run = await agent.run(QUESTION)

    assert run.tool_calls == 0
    assert "没有这个工具" in run.steps[0].error
    # 提示里要带上可用工具名，模型才知道怎么改
    assert "search_posts" in chat.calls[1][1].content


async def test_empty_question_is_rejected() -> None:
    agent = Agent(chat=ScriptedChat([]), tools=ToolBox([tool("search_posts")]))

    with pytest.raises(ValueError, match="问题"):
        await agent.run("   ")


# --------------------------------------------------------------- 与真实工具的接线


async def test_search_tool_returns_citable_snippets() -> None:
    search = SearchPostsTool(pipeline=build_pipeline())

    result = await search.run({"question": "一年写十八万字", "topK": 2})

    assert result.citations, "语料里有答案却检索不到，说明接线错了"
    citation = result.citations[0]
    assert citation.post_id == 1
    assert citation.snippet, "引用必须带原文片段，否则无法定位"
    assert citation.score is not None, "分数要带回来（前端按相关性展示）"
    assert "postId=1" in result.summary, "摘要里要有 postId，模型才能据此标引用"


async def test_search_tool_reports_no_evidence_as_a_result() -> None:
    """「没有依据」是结果不是错误：Agent 该据此收尾，而不是换个说法再查一遍。"""
    search = SearchPostsTool(pipeline=build_pipeline(refuse=True))

    result = await search.run({"question": "怎么养一只会写诗的猫？"})

    assert result.citations == []
    assert "没有" in result.summary
    assert result.label == "无命中"


async def test_search_tool_requires_a_question() -> None:
    search = SearchPostsTool(pipeline=build_pipeline())

    result = await search.run({})

    assert result.label == "参数缺失"


async def test_style_tool_never_returns_author_sentences() -> None:
    """画像工具同样受「不引用原句」约束 —— 它会把结果直接喂给模型。"""
    body = "其实我写得慢。一天五百字，不多。\n" * 60
    style = AuthorStyleTool(posts=[_Post("慢一点", body)])

    result = await style.run({})

    assert "样本" in result.summary, "样本够时要给出画像，而不是「样本不足」"
    assert "其实我写得慢。" not in result.summary


async def test_style_tool_says_so_when_samples_are_short() -> None:
    style = AuthorStyleTool(posts=[_Post("刚开张", "今天开始写。\n")])

    result = await style.run({})

    assert result.label == "样本不足"
    assert "不足" in result.summary


def test_read_only_tools_assembly_follows_what_is_available() -> None:
    only_search = read_only_tools(pipeline=build_pipeline())
    assert [spec.name for spec in only_search] == ["search_posts"]

    both = read_only_tools(pipeline=build_pipeline(), style_posts=[_Post("慢一点", "正文" * 200)])
    assert [spec.name for spec in both] == ["search_posts", "author_style"]
    assert all(spec.read_only for spec in both), "装配出来的工具必须全是只读"


class _Post:
    """与 style.py 的 PostLike 协议一致的最小样本类型。"""

    def __init__(self, title: str, plain: str) -> None:
        self._title = title
        self._plain = plain

    @property
    def title(self) -> str:
        return self._title

    @property
    def plain(self) -> str:
        return self._plain
