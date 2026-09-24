"""Provider 层：确定性 Fake、按角色路由、能力不匹配时的可操作错误。

这些用例保证「业务代码只认角色、不认厂商」这条边界成立 ——
换模型只改配置，不需要改任何调用处。
"""

from __future__ import annotations

import dataclasses

import pytest

from app.providers import (
    ChatMessage,
    FakeProvider,
    MessageRole,
    ProviderCapabilities,
    ProviderConfig,
    ProviderError,
    ProviderRegistry,
    UnsupportedCapabilityError,
)


def _config(role: str, capability: str, *, provider: str = "fake") -> ProviderConfig:
    caps = ProviderCapabilities(
        chat=capability == "chat",
        embedding=capability == "embedding",
        rerank=capability == "rerank",
    )
    return ProviderConfig(
        role=role,
        provider=provider,
        base_url="http://fake.local",
        model="fake-chat",
        dimension=32 if capability == "embedding" else None,
        capabilities=caps,
    )


def test_config_fingerprint_ignores_secret() -> None:
    """指纹用于日志与缓存键，绝不能因为密钥变化而变化（否则等于把密钥编码进指纹）。"""
    base = _config("chat", "chat")
    rotated = dataclasses.replace(base, api_key="sk-another-secret")

    assert base.fingerprint() == rotated.fingerprint()
    assert "sk-" not in str(base.safe_summary())


def test_safe_summary_has_no_url_or_key() -> None:
    config = ProviderConfig(
        role="chat",
        provider="openai_compatible",
        base_url="https://api.deepseek.com/v1",
        model="deepseek-chat",
        api_key="sk-secret-value",
        capabilities=ProviderCapabilities(chat=True),
    )

    summary = str(config.safe_summary())

    assert "sk-secret-value" not in summary
    assert "api.deepseek.com" not in summary
    assert "deepseek-chat" in summary  # 模型名可以出现，它是排查问题的关键信息


async def test_fake_chat_is_deterministic_and_marks_itself() -> None:
    provider = FakeProvider(_config("chat", "chat"))
    messages = [ChatMessage(MessageRole.USER, "星笺为什么把文章比作星辰？")]

    first = await provider.chat(messages)
    second = await provider.chat(messages)

    assert first.text == second.text, "Fake 必须确定性：评测台要靠它做可复现对比"
    assert first.text.startswith("[fake]")
    assert "星辰" in first.text, "回显最后一条用户输入，便于端到端断言链路通"
    assert first.usage.model == FakeProvider.MODEL_TAG
    assert first.usage.total_tokens == first.usage.prompt_tokens + first.usage.completion_tokens


async def test_fake_embedding_dimension_and_determinism() -> None:
    provider = FakeProvider(_config("embedding", "embedding"))

    response = await provider.embed(["把文章写成星图", "把文章写成星图", "另一种说法"])

    assert response.dimension == 32
    assert len(response.vectors) == 3
    assert response.vectors[0] == response.vectors[1], "同一文本必须得到同一向量"
    assert response.vectors[0] != response.vectors[2], "不同文本应给出不同向量"
    assert all(len(vector) == 32 for vector in response.vectors)


async def test_fake_embedding_matches_configured_dimension() -> None:
    """维度跟着配置走：换嵌入模型时集合维度要能对得上。"""
    config = _config("embedding", "embedding")
    provider = FakeProvider(dataclasses.replace(config, dimension=128))

    response = await provider.embed(["维度测试"])

    assert response.dimension == 128


async def test_fake_rerank_orders_by_similarity_and_respects_top_n() -> None:
    provider = FakeProvider(_config("rerank", "rerank"))
    documents = ["把文章写成星图", "今天吃什么", "星图的密度由写作频率决定"]

    full = await provider.rerank("星图", documents)
    limited = await provider.rerank("星图", documents, top_n=2)

    assert len(full.results) == 3
    assert len(limited.results) == 2
    assert full.results[0].score >= full.results[-1].score, "必须按分数降序"
    assert {item.index for item in full.results} == {0, 1, 2}, "下标必须覆盖全部候选"


async def test_fake_rerank_rejects_empty_candidates() -> None:
    provider = FakeProvider(_config("rerank", "rerank"))

    with pytest.raises(ProviderError):
        await provider.rerank("星图", [])


def test_registry_routes_by_role() -> None:
    registry = ProviderRegistry(
        [
            _config("chat", "chat"),
            _config("embedding", "embedding"),
            _config("rerank", "rerank"),
        ]
    )

    assert registry.has("chat")
    assert isinstance(registry.chat_model("chat"), FakeProvider)
    assert isinstance(registry.embedding_model(), FakeProvider)
    assert isinstance(registry.rerank_model(), FakeProvider)
    assert registry.roles == ["chat", "embedding", "rerank"]


def test_registry_defaults_to_canonical_role_names() -> None:
    """不传角色名时用规范角色：调用处写 `chat_model()` 就该拿到 chat。"""
    registry = ProviderRegistry([_config("chat", "chat"), _config("embedding", "embedding")])

    assert registry.chat_model() is registry.chat_model("chat")
    assert registry.embedding_model() is registry.embedding_model("embedding")


def test_registry_reports_missing_role_with_actionable_message() -> None:
    registry = ProviderRegistry([])

    with pytest.raises(UnsupportedCapabilityError) as excinfo:
        registry.chat_model("chat")

    assert "尚未配置" in str(excinfo.value)
    assert "模型配置" in (excinfo.value.detail or ""), "错误里要告诉人去哪里配"


def test_registry_rejects_capability_mismatch() -> None:
    """给 embedding 角色配了纯 chat 模型：取实例时就该失败，而不是调用中途才炸。"""
    registry = ProviderRegistry([_config("embedding", "chat")])

    with pytest.raises(UnsupportedCapabilityError) as excinfo:
        registry.embedding_model()

    assert "embedding" in str(excinfo.value)
    assert excinfo.value.retryable is False, "配置错误不该被当成可重试故障"


def test_registry_rejects_unknown_role() -> None:
    registry = ProviderRegistry([_config("chat", "chat")])

    with pytest.raises(ProviderError) as excinfo:
        registry.chat_model("nope")

    assert "未知的模型角色" in str(excinfo.value)


def test_registry_rejects_unknown_provider_implementation() -> None:
    registry = ProviderRegistry([_config("chat", "chat", provider="some-vendor-sdk")])

    with pytest.raises(ProviderError) as excinfo:
        registry.chat_model()

    assert "openai_compatible" in (excinfo.value.detail or "")


def test_registry_reregister_replaces_previous_config() -> None:
    """面板按角色唯一保存：重新注册应换掉旧配置，而不是并存两份。"""
    registry = ProviderRegistry([_config("chat", "chat")])
    updated = ProviderConfig(
        role="chat",
        provider="fake",
        base_url="http://fake.local",
        model="fake-chat-v2",
        capabilities=ProviderCapabilities(chat=True),
    )

    registry.register(updated)

    assert registry.roles == ["chat"]
    assert registry.config_of("chat") is not None
    assert registry.config_of("chat").model == "fake-chat-v2"  # type: ignore[union-attr]


async def test_registry_aclose_is_idempotent() -> None:
    registry = ProviderRegistry([_config("chat", "chat")])
    registry.chat_model()

    await registry.aclose()
    await registry.aclose()  # 重复关闭不应抛异常
