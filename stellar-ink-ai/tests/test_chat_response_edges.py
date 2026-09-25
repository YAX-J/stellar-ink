"""真实模型特有的两个坑：**空输出不等于拒答**，以及模型名要取得到。

这两条都只在接上真实 Provider 之后才暴露，而且都不报错：

1. 推理模型（DeepSeek `deepseek-flash` / `deepseek-reasoner`）先产出 `reasoning_content`，
   那段也占 `completion_tokens`。`max_tokens` 给小了就是「content 为空 + finish_reason=length」——
   原来的 `refused` 把它算成「模型拒答」，用户会去查安全过滤与提示词，而真相是预算不够。
2. 旧代码用 `getattr(model, "model")` 取展示用的模型名，而真实 Provider 的模型名在
   `config.model` 上 —— 流式问答的 `meta.model` 与 `done.usage.model` 一直是 `unknown`，
   链路完全正常却看起来像「没接上模型」。
"""

from __future__ import annotations

import pytest

from app.providers.base import model_tag_of
from app.providers.fake import FakeProvider
from app.providers.models import ChatResponse, ProviderCapabilities, ProviderConfig, TokenUsage
from app.providers.openai_compatible import OpenAICompatibleProvider
from app.rag.writing import FakeCopilotChat, WritingCopilot, WritingSettings
from app.schemas.writing import WritingSuggestRequest


def _chat_config() -> ProviderConfig:
    return ProviderConfig(
        role="chat",
        provider="openai_compatible",
        base_url="http://model.invalid/v1",
        model="deepseek-flash",
        api_key="not-used",
        capabilities=ProviderCapabilities(chat=True),
    )


# ------------------------------------------------------------------ 空输出分类


def test_empty_output_with_length_is_truncation_not_refusal() -> None:
    """实测形态：`max_tokens=16` 时 deepseek-flash 返回 content 为空 + finish_reason=length。"""
    response = ChatResponse(text="", finish_reason="length", usage=TokenUsage())

    assert response.truncated is True
    assert response.empty is True
    assert response.refused is False, "预算烧完不是拒答"


def test_empty_output_with_stop_is_a_refusal() -> None:
    """正常结束却一个字都没给：这才是模型自己答不了。"""
    response = ChatResponse(text="   \n", finish_reason="stop", usage=TokenUsage())

    assert response.refused is True
    assert response.truncated is False


def test_content_filter_is_always_a_refusal() -> None:
    """内容过滤即使带了半句话也算拒答：那是安全策略，不是内容问题。"""
    response = ChatResponse(text="我不能", finish_reason="content_filter", usage=TokenUsage())

    assert response.refused is True


# ------------------------------------------------------------------ 模型名


def test_model_tag_prefers_the_offline_marker() -> None:
    """离线桩要自报家门：前端靠 `fake` 显示「离线自测」，不能被配置里的模型名盖掉。"""
    assert model_tag_of(FakeProvider()) == "fake"
    assert model_tag_of(FakeCopilotChat()) == "fake-copilot"


def test_model_tag_reads_the_provider_config() -> None:
    """真实 Provider 的模型名在 `config.model` 上 —— 取不到就会一路显示 unknown。"""
    provider = OpenAICompatibleProvider(_chat_config())

    assert model_tag_of(provider) == "deepseek-flash"


def test_model_tag_falls_back_to_unknown() -> None:
    """什么都不带的桩：给一个明确的 unknown，而不是空串（空串会让前端显示「模型：」）。"""

    class _Bare:
        pass

    assert model_tag_of(_Bare()) == "unknown"


# ------------------------------------------------------------------ Copilot 预算


async def test_copilot_says_budget_is_the_problem_not_the_format() -> None:
    """Copilot 拿不到任何输出且被截断：要说「预算不够」，不能说「解析不出候选」。

    后者会让作者去改提示词或怀疑模型不听话，而真正要做的是把 maxTokens 调大。
    """

    class _Truncated:
        async def chat(self, messages, *, temperature=None, max_tokens=None) -> ChatResponse:
            return ChatResponse(text="", finish_reason="length", usage=TokenUsage())

    copilot = WritingCopilot(chat=_Truncated(), settings=WritingSettings())  # type: ignore[arg-type]

    with pytest.raises(ValueError, match="token 预算"):
        await copilot.suggest(WritingSuggestRequest(task="polish", draft="今晚星星很多。"))


async def test_copilot_still_parses_a_normal_partial_answer() -> None:
    """被截断但已产出可解析的候选时照常返回：能用的部分不该因为截断被丢掉。"""

    class _Partial:
        async def chat(self, messages, *, temperature=None, max_tokens=None) -> ChatResponse:
            return ChatResponse(
                text='[{"text":"今晚星星很多。","rationale":"保持原样"}]',
                finish_reason="length",
                usage=TokenUsage.of(10, 20, latency_ms=3, model="deepseek-flash"),
            )

    copilot = WritingCopilot(chat=_Partial(), settings=WritingSettings())  # type: ignore[arg-type]

    result = await copilot.suggest(WritingSuggestRequest(task="polish", draft="今晚星星很多。"))

    assert result.candidates
    assert result.usage.model == "deepseek-flash"
