"""司职骨架（`app/agents/`）：注册表、白名单、默认形态、纯生成。

这一层守的是**边界**而不是能力：

1. **缺省司职 = 今天的形态**：骨架化的第一刀不许改变默认行为，
   所以默认司职、默认预算、默认提示词都要能被逐字断言（改一个字就该红）；
2. **未知名字绝不猜、绝不回退**：回退默认的后果是「拼错名字照样跑完，
   账单里记的是另一个岗位」，比报错难查得多；
3. **工具是白名单 + 装不进来两道门叠加**：司职只声明要哪几个只读工具，
   而 `ToolBox` 依旧拒绝写工具 —— 少了任何一道，「Agent 能写库」就会重新变成可能；
4. **无工具司职真的不花钱**：没有摘录时一次模型调用都不发。
"""

from __future__ import annotations

import ast
from dataclasses import FrozenInstanceError
from pathlib import Path
from typing import Any

import pytest

from app.agents import answerer, registry, searcher, verifier
from app.agents.profile import AgentProfile
from app.providers.fake import FakeProvider
from app.providers.models import ChatMessage, ChatResponse, TokenUsage
from app.rag.agent import DEFAULT_MAX_STEPS, DEFAULT_MAX_TOOL_CALLS, Agent, AgentSettings, ToolBox
from app.rag.agent_tools import read_only_tools
from app.rag.pipeline import IndexedChunk, RetrievalConfig, RetrievalPipeline
from app.schemas.common import DoneReason

QUESTION = "一年写十八万字的方法是什么？"


class ScriptedChat:
    """按脚本回答的模型桩（与 `tests/test_agent.py` 同形）。"""

    MODEL_TAG = "scripted"

    def __init__(self, reply: str = "每天五百字。", *, finish_reason: str = "stop") -> None:
        self._reply = reply
        self._finish_reason = finish_reason
        self.calls: list[list[ChatMessage]] = []
        self.kwargs: list[dict[str, Any]] = []

    async def chat(
        self,
        messages: list[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> ChatResponse:
        self.calls.append(list(messages))
        self.kwargs.append({"temperature": temperature, "max_tokens": max_tokens})
        return ChatResponse(
            text=self._reply,
            finish_reason=self._finish_reason,
            usage=TokenUsage(model=self.MODEL_TAG),
        )


def tiny_pipeline() -> RetrievalPipeline:
    """一条只装了一个子块、且不开 dense 的检索管线：用来验证**装配**而不是检索质量。"""
    chunk = IndexedChunk(
        chunk_id="c1",
        post_id=1,
        title="写作的复利",
        text="写作的复利\n一年写十八万字靠的是每天五百字。",
        payload={"text": "一年写十八万字靠的是每天五百字。", "chunkIndex": 0},
    )
    return RetrievalPipeline(
        corpus=[chunk],
        config=RetrievalConfig(enable_dense=False),
        embedder=FakeProvider(),
    )


# --------------------------------------------------------------- 注册表


def test_registry_holds_the_three_declared_profiles() -> None:
    assert set(registry.AGENTS) == {"answerer", "searcher", "verifier"}


def test_default_agent_is_the_searcher() -> None:
    """缺省司职就是今天 `/agent/ask` 的形态 —— 这一条是「不劣化」的锚点。"""
    assert registry.DEFAULT_AGENT == "searcher"
    assert registry.DEFAULT_AGENT in registry.EXECUTABLE_AGENTS


def test_default_profile_keeps_todays_prompt_and_budget() -> None:
    """默认司职的提示词与预算逐字等于今天：多一步、少一次调用都算行为变化。"""
    profile = registry.get_profile(None)

    assert profile.system_prompt == Agent.SYSTEM_PROMPT
    assert profile.tool_names == ("search_posts",)
    assert profile.settings.max_steps == DEFAULT_MAX_STEPS == 4
    assert profile.settings.max_tool_calls == DEFAULT_MAX_TOOL_CALLS == 6


def test_searcher_prompt_has_exactly_one_source() -> None:
    """提示词只有一份（`searcher`）：`Agent.SYSTEM_PROMPT` 是别名，不是副本。"""
    assert Agent.SYSTEM_PROMPT == searcher.SYSTEM_PROMPT
    assert searcher.PROFILE.system_prompt == Agent.SYSTEM_PROMPT


@pytest.mark.parametrize("name", [None, "", "   ", "searcher", "SEARCHER", " Searcher "])
def test_missing_or_case_differing_name_falls_back_to_the_default(name: str | None) -> None:
    """留空与大小写差异落到缺省；但**拼错**绝不落到缺省（见下一条）。"""
    assert registry.get_profile(name).name == "searcher"


@pytest.mark.parametrize("name", ["search", "searcherr", "深挖", "answer", "verifier2"])
def test_unknown_name_lists_the_options(name: str) -> None:
    with pytest.raises(registry.UnknownAgentError) as error:
        registry.get_profile(name)

    message = str(error.value)
    assert name in message
    for option in registry.AGENTS:
        assert option in message, f"错误消息里必须列出可选司职：{option}"
    assert error.value.available == tuple(registry.AGENTS)


def test_unknown_name_is_not_a_value_error() -> None:
    """端点上 `ValueError` 已经表示「模型没按格式回答」：混用一个类型会翻错错误。"""
    assert issubclass(registry.UnknownAgentError, LookupError)
    assert not issubclass(registry.UnknownAgentError, ValueError)


def test_list_profiles_is_sorted_and_complete() -> None:
    profiles = registry.list_profiles()

    assert [profile.name for profile in profiles] == ["answerer", "searcher", "verifier"]
    assert all(isinstance(profile, AgentProfile) for profile in profiles)


def test_profiles_are_frozen() -> None:
    """司职是**数据**：一旦可改，就会出现「某个请求把它改了，之后所有请求都变了」。"""
    with pytest.raises(FrozenInstanceError):
        registry.AGENTS["searcher"].title = "改一下"  # type: ignore[misc]


def test_executable_agents_are_registered() -> None:
    """「接了线」的名字必须都在注册表里 —— 否则前端列不出来，用户也传不进来。"""
    assert registry.EXECUTABLE_AGENTS <= set(registry.AGENTS)
    # A2 起核验已接线（它做的是确定性核验，不是「第二个 searcher」）
    assert "verifier" in registry.EXECUTABLE_AGENTS


def test_skeleton_agents_are_registered_and_not_executable() -> None:
    """三个集合的**划分**必须覆盖注册表：每个岗位要么能跑，要么被显式标成骨架。

    少了这条，加一个新司职时最容易发生的事是「忘了接线」——
    而它看起来不是错误，是「这个岗位答得跟生成助手一样」。
    """
    assert registry.SKELETON_AGENTS <= set(registry.AGENTS)
    assert not (registry.SKELETON_AGENTS & registry.EXECUTABLE_AGENTS)
    assert registry.EXECUTABLE_AGENTS | registry.SKELETON_AGENTS == set(registry.AGENTS)


def test_model_free_agents_are_executable_and_registered() -> None:
    """「一个模型都不调」是一份**显式**名单：它不在可执行集合里就没有意义。

    这一份的存在理由不是分类癖：`MODEL_FREE_AGENTS` 是端点分派与
    「零模型调用」断言的锚点（见 `tests/test_agents_verify.py`）。
    """
    assert registry.MODEL_FREE_AGENTS <= registry.EXECUTABLE_AGENTS
    assert registry.MODEL_FREE_AGENTS <= set(registry.AGENTS)
    assert registry.MODEL_FREE_AGENTS == {"verifier"}


def test_executable_agents_all_have_a_buildable_tool_set() -> None:
    """可执行司职的工具白名单必须**真的装得出来**：要么空（走生成/核验路径），要么全在工厂里。

    这正是端点上那句「既没接线也没标骨架 → 500」想挡的事，在注册表层先拦一道：
    `answerer` / `verifier` 白名单为空是合法的（它们不走循环），
    但「声明了 `search_posts` 而工厂里没有」是配置事故。
    """
    from app.api.v1.agent import PROFILE_TOOLS
    from app.rag.agent_tools import read_only_tools

    # 工具工厂是**参数化**的：给了 pipeline 才会造出 search_posts
    factory_names = {spec.name for spec in read_only_tools(pipeline=tiny_pipeline())}
    assert factory_names == {"search_posts"}, "工厂里出现新工具时，司职白名单要同步登记"

    for name in sorted(registry.EXECUTABLE_AGENTS):
        profile = registry.get_profile(name)
        assert set(profile.tool_names) <= PROFILE_TOOLS, f"{name} 声明了未登记的工具"
        assert set(profile.tool_names) <= factory_names, f"{name} 声明了工厂造不出的工具"
        # 不声明工具的司职必须能说清「那你走哪条路」：生成（要模型）或核验（不调模型）
        if not profile.tool_names:
            assert name in registry.MODEL_FREE_AGENTS or name == answerer.PROFILE.name, (
                f"{name} 没有工具，又不属于已知的无工具路径"
            )


def test_skeleton_agents_declare_no_tools() -> None:
    """骨架司职**不声明任何工具**：声明了就等于说「它能查东西」，而它还没接线。

    A2 之后 `SKELETON_AGENTS` 是空的（核验已接线）；这条断言留着，
    等下一个只建骨架的岗位进来时它自动生效。
    """
    for name in sorted(registry.SKELETON_AGENTS):
        assert registry.get_profile(name).tool_names == ()


def test_verifier_is_visible_and_executable_but_model_free() -> None:
    """核验员**可见**（前端能列出这个岗位）、**可执行**，且**不调模型**。

    A1 时它只是骨架；A2 把确定性核验接上了 —— 但接上不等于「它能生成答案」：
    `tool_names` 仍然是空的，而且它不该出现在 `/agent/ask` 的可用路径里。
    """
    profile = registry.get_profile("verifier")

    assert profile.name == "verifier"
    assert profile.tool_names == ()
    assert profile.system_prompt == verifier.SYSTEM_PROMPT
    assert profile in registry.list_profiles()


def test_searcher_only_declares_tools_that_can_be_built() -> None:
    """司职声明的工具名必须真的能从 `read_only_tools` 里装出来（装配层按白名单取）。"""
    available = {spec.name for spec in read_only_tools(pipeline=tiny_pipeline())}

    assert set(searcher.PROFILE.tool_names) <= available


def test_toolbox_still_refuses_an_empty_tool_set() -> None:
    """空工具集也过不了 `ToolBox` —— 无工具的司职因此走生成路径，而不是一个瞎猜的循环。"""
    with pytest.raises(ValueError, match="空"):
        ToolBox([])


def test_write_tools_cannot_even_be_produced_by_the_tool_factory() -> None:
    """白名单之上还有一道门：工具工厂**产不出**写工具。

    `ToolBox` 会在装配时拒绝 `read_only=False`，但那是第二道门；
    第一道门是「唯一工具来源 `read_only_tools()` 里根本没有写操作」——
    只要这条成立，司职声明的任何名字都不可能是写工具。
    """
    specs = read_only_tools(
        pipeline=tiny_pipeline(),
        style_posts=[{"title": "写作的复利", "content": "每天五百字。"}],
    )

    assert specs, "至少要有 search_posts，否则这个断言是空跑"
    assert all(spec.read_only for spec in specs)
    # 装进 ToolBox 不报错，才说明这批工具真的能进循环
    assert ToolBox(specs).names == [spec.name for spec in specs]


# --------------------------------------------------------------- answerer（纯生成）


def test_answerer_module_does_not_touch_retrieval() -> None:
    """answerer 只负责生成：它不该 import 检索管道（检索只有一条，在装配层）。

    查的是 **import 语句**而不是整个文件：模块 docstring 里刻意提到了
    `RetrievalPipeline` 来解释「为什么不要它」，扫全文会把那段说明当成违规。
    """
    tree = ast.parse(Path(answerer.__file__ or "").read_text(encoding="utf-8"))
    imported: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.add(node.module)
            imported.update(f"{node.module}.{alias.name}" for alias in node.names)

    assert not [name for name in imported if "pipeline" in name], "生成层不该持有检索管道"
    assert not [name for name in imported if "corpus" in name], "生成层不该自己取语料"


def test_answerer_prompt_without_excerpts_says_there_is_none() -> None:
    """没有摘录时**不能**编一个空的「站内摘录」段：那等于骗模型下面有证据。"""
    prompt = answerer.build_user_prompt(QUESTION)

    assert QUESTION in prompt
    assert "站内摘录：" not in prompt
    assert "没有依据" in prompt


def test_answerer_prompt_numbers_the_excerpts() -> None:
    prompt = answerer.build_user_prompt(
        QUESTION,
        [
            answerer.Excerpt(number=1, title="写作的复利", text="每天五百字。"),
            answerer.Excerpt(number=2, title="一次 23 秒的请求", text="超时要显式写。"),
        ],
    )

    assert "[1]《写作的复利》：每天五百字。" in prompt
    assert "[2]《一次 23 秒的请求》：超时要显式写。" in prompt


async def test_answerer_generates_once_and_reports_the_model() -> None:
    chat = ScriptedChat("每天五百字。")
    runner = answerer.Answerer(chat=chat, settings=AgentSettings(max_steps=1, max_tool_calls=1))

    run = await runner.generate(
        answerer.SYSTEM_PROMPT,
        QUESTION,
        [answerer.Excerpt(number=1, title="写作的复利", text="每天五百字。")],
    )

    assert run.answer == "每天五百字。"
    assert run.done_reason == DoneReason.STOP
    assert run.evidence_sufficient is True
    assert run.usage_model == "scripted"
    assert len(chat.calls) == 1, "一次生成就是一次调用"
    assert chat.calls[0][0].role.value == "system"
    assert chat.calls[0][0].content == answerer.SYSTEM_PROMPT


async def test_answerer_reports_truncation_as_length_not_refusal() -> None:
    """被 token 预算截断**不是**拒答：两者的处置完全不同（改 maxTokens vs 换问题）。"""
    chat = ScriptedChat("", finish_reason="length")
    run = await answerer.Answerer(chat=chat).generate(answerer.SYSTEM_PROMPT, QUESTION)

    assert run.done_reason == DoneReason.LENGTH
    assert run.evidence_sufficient is False
    assert "maxTokens" in run.answer or "token" in run.answer


async def test_answerer_refuses_when_the_model_says_nothing() -> None:
    chat = ScriptedChat("")
    run = await answerer.Answerer(chat=chat).generate(answerer.SYSTEM_PROMPT, QUESTION)

    assert run.done_reason == DoneReason.REFUSED
    assert run.evidence_sufficient is False


async def test_answerer_rejects_a_blank_question() -> None:
    with pytest.raises(ValueError, match="问题不能为空"):
        await answerer.Answerer(chat=ScriptedChat()).generate(answerer.SYSTEM_PROMPT, "   ")


async def test_answerer_settings_come_from_the_profile() -> None:
    """司职声明的模型参数必须真的传下去（配了没人读，看起来就像「模型不稳定」）。"""
    chat = ScriptedChat()
    runner = answerer.Answerer(chat=chat, settings=answerer.PROFILE.settings)

    await runner.generate(answerer.SYSTEM_PROMPT, QUESTION)

    assert chat.kwargs[0]["temperature"] == answerer.PROFILE.settings.temperature
    assert chat.kwargs[0]["max_tokens"] == answerer.PROFILE.settings.max_tokens


def test_refusal_run_spends_no_model_call() -> None:
    """没有摘录 = 没有依据：直接收尾，绝不叫模型（省一次钱，也避免它对着空上下文编）。"""
    run = answerer.refusal_run(latency_ms=7)

    assert run.done_reason == DoneReason.REFUSED
    assert run.evidence_sufficient is False
    assert run.usage_model is None
    assert run.latency_ms == 7


def test_profile_settings_are_used_as_is_for_tool_free_profiles() -> None:
    """无工具司职的预算字面就是「一步、零工具调用机会」。"""
    for profile in (answerer.PROFILE, verifier.PROFILE):
        assert profile.tool_names == ()
        assert profile.settings.max_steps == 1
