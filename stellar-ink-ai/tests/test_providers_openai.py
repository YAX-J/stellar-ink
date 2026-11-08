"""OpenAI 兼容 Provider：请求形状、响应解析与错误分类。

用 `httpx.MockTransport` 而不是真的打网络：这些用例要断言的是**协议细节**
（打哪个路径、带什么头、参数怎么拼、错误怎么归类），发真实请求既慢又不确定。

对照真实厂商时的用法：只需把 base_url / model / api_key 换成面板里填的值，
本文件验证的路径与报文形状不会变 —— 这就是「只做 OpenAI 兼容」的收益。
"""

from __future__ import annotations

import dataclasses
import json
from typing import Any

import httpx
import pytest

from app.providers import (
    ChatMessage,
    MessageRole,
    OpenAICompatibleProvider,
    ProviderAuthError,
    ProviderCapabilities,
    ProviderConfig,
    ProviderError,
    ProviderRateLimitError,
    ProviderTimeoutError,
    ProviderUnavailableError,
    UnsupportedCapabilityError,
)
from app.providers.retry import RetryPolicy

CHAT_CONFIG = ProviderConfig(
    role="chat",
    provider="openai_compatible",
    base_url="https://api.deepseek.com/v1",
    model="deepseek-chat",
    api_key="sk-test-key",
    capabilities=ProviderCapabilities(chat=True),
)
EMBEDDING_CONFIG = ProviderConfig(
    role="embedding",
    provider="openai_compatible",
    base_url="https://api.siliconflow.cn/v1",
    model="BAAI/bge-m3",
    api_key="sk-test-key",
    dimension=3,
    capabilities=ProviderCapabilities(embedding=True),
)
RERANK_CONFIG = ProviderConfig(
    role="rerank",
    provider="openai_compatible",
    base_url="https://api.siliconflow.cn/v1",
    model="BAAI/bge-reranker-v2-m3",
    api_key="sk-test-key",
    capabilities=ProviderCapabilities(rerank=True),
)


class _Recorder:
    """记录发出的请求，供断言路径/请求头/请求体。"""

    def __init__(self, response: httpx.Response) -> None:
        self.response = response
        self.requests: list[httpx.Request] = []
        self.payloads: list[dict[str, Any]] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if request.content:
            self.payloads.append(json.loads(request.content.decode("utf-8")))
        return self.response


def _provider(
    config: ProviderConfig, response: httpx.Response
) -> tuple[OpenAICompatibleProvider, _Recorder]:
    """构造一个**关闭重试**的 provider。

    这些用例断言的是「状态码 → 错误分类」与「请求体长什么样」；重试退避是另一件事，
    由 `test_provider_retry.py` 专门测。不关掉的话，5 个失败态用例会各自真等 5.6 秒
    （800ms + 1.6s + 3.2s），整个测试套件从 8 秒变成 35 秒 —— 而它们本来只想看一眼错误类型。
    """
    recorder = _Recorder(response)
    provider = OpenAICompatibleProvider(
        config,
        transport=httpx.MockTransport(recorder.handler),
        retry=RetryPolicy.disabled(),
    )
    return provider, recorder


def _json(payload: dict[str, Any], status_code: int = 200) -> httpx.Response:
    return httpx.Response(status_code, json=payload)


async def test_chat_sends_bearer_token_and_openai_payload() -> None:
    provider, recorder = _provider(
        CHAT_CONFIG,
        _json(
            {
                "choices": [
                    {"message": {"content": "星笺把文章比作星辰。"}, "finish_reason": "stop"}
                ],
                "usage": {"prompt_tokens": 12, "completion_tokens": 8},
            }
        ),
    )

    response = await provider.chat([ChatMessage(MessageRole.USER, "为什么是星辰？")])

    request = recorder.requests[0]
    assert request.url.path == "/v1/chat/completions"
    assert request.headers["authorization"] == "Bearer sk-test-key"
    assert recorder.payloads[0]["model"] == "deepseek-chat"
    assert recorder.payloads[0]["messages"] == [{"role": "user", "content": "为什么是星辰？"}]
    assert recorder.payloads[0]["stream"] is False
    assert response.text == "星笺把文章比作星辰。"
    assert response.usage.total_tokens == 20
    assert response.usage.model == "deepseek-chat"
    await provider.aclose()


async def test_chat_applies_configured_temperature_and_max_tokens() -> None:
    config = dataclasses.replace(CHAT_CONFIG, temperature=0.3, max_tokens=256)
    provider, recorder = _provider(
        config, _json({"choices": [{"message": {"content": "ok"}}], "usage": {}})
    )

    await provider.chat([ChatMessage(MessageRole.USER, "hi")])

    assert recorder.payloads[0]["temperature"] == 0.3
    assert recorder.payloads[0]["max_tokens"] == 256
    await provider.aclose()


async def test_chat_explicit_temperature_overrides_config() -> None:
    config = dataclasses.replace(CHAT_CONFIG, temperature=0.3)
    provider, recorder = _provider(
        config, _json({"choices": [{"message": {"content": "ok"}}], "usage": {}})
    )

    await provider.chat([ChatMessage(MessageRole.USER, "hi")], temperature=0.9)

    assert recorder.payloads[0]["temperature"] == 0.9
    await provider.aclose()


async def test_chat_marks_empty_content_as_refusal() -> None:
    """拒答或内容被过滤时不该被当成正常答案（M3 的「无依据就明说」靠这个判断）。"""
    provider, _ = _provider(CHAT_CONFIG, _json({"choices": [{"message": {"content": ""}}]}))

    response = await provider.chat([ChatMessage(MessageRole.USER, "hi")])

    assert response.refused
    await provider.aclose()


async def test_embedding_keeps_input_order_and_reports_dimension() -> None:
    provider, recorder = _provider(
        EMBEDDING_CONFIG,
        _json(
            {"data": [{"embedding": [0.1, 0.2, 0.3]}, {"embedding": [0.4, 0.5, 0.6]}], "usage": {}}
        ),
    )

    response = await provider.embed(["第一篇", "第二篇"])

    assert recorder.requests[0].url.path == "/v1/embeddings"
    assert recorder.payloads[0]["input"] == ["第一篇", "第二篇"]
    assert response.dimension == 3
    assert response.vectors == [[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]]
    await provider.aclose()


async def test_embedding_rejects_count_mismatch() -> None:
    """少一条会让「向量 ↔ 原文」错位，必须失败而不是凑合。"""
    provider, _ = _provider(
        EMBEDDING_CONFIG, _json({"data": [{"embedding": [0.1, 0.2, 0.3]}], "usage": {}})
    )

    with pytest.raises(ProviderUnavailableError, match="数量与输入不一致"):
        await provider.embed(["第一篇", "第二篇"])
    await provider.aclose()


@pytest.mark.parametrize(
    "base_url",
    [
        "https://maas.example.com/compatible-mode/v1",
        "https://maas.example.com/compatible-mode/v1/",
        "https://maas.example.com/proxy/compatible-mode/v1",
        "https://maas.example.com/compatible-api/v1",
    ],
)
async def test_qwen_embedding_batches_corpus_and_preserves_alignment(base_url: str) -> None:
    """整库超过厂商上限时拆批，尾批、向量顺序与用量都不能丢。"""
    batches: list[list[str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        batch = payload["input"]
        batches.append(batch)
        assert request.url.path == httpx.URL(base_url).path.rstrip("/") + "/embeddings"
        assert payload["model"] == "qwen3.7-text-embedding-flash"
        assert len(batch) <= 10
        return _json({
            "data": [{"embedding": [float(text), 0.0, 1.0]} for text in batch],
            "usage": {"total_tokens": len(batch) * 2},
        })

    config = dataclasses.replace(
        EMBEDDING_CONFIG, base_url=base_url, model="qwen3.7-text-embedding-flash"
    )
    provider = OpenAICompatibleProvider(config, transport=httpx.MockTransport(handler))
    try:
        texts = [str(index) for index in range(39)]
        response = await provider.embed(texts)
        assert [len(batch) for batch in batches] == [10, 10, 10, 9]
        assert [text for batch in batches for text in batch] == texts
        assert response.vectors == [[float(index), 0.0, 1.0] for index in range(39)]
        assert response.dimension == 3
        assert response.usage.prompt_tokens == response.usage.total_tokens == 78
        assert response.usage.model == config.model
    finally:
        await provider.aclose()


async def test_qwen_embedding_failed_batch_never_returns_partial_vectors() -> None:
    """第二批失败即停止，不把前一批冒充完整嵌入结果。"""
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 2:
            return _json({"error": {"message": "bad input"}}, status_code=400)
        return _json({"data": [{"embedding": [1.0, 0.0, 0.0]}] * 10})

    config = dataclasses.replace(
        EMBEDDING_CONFIG, base_url="https://maas.example.com/compatible-mode/v1"
    )
    provider = OpenAICompatibleProvider(config, transport=httpx.MockTransport(handler))
    try:
        with pytest.raises(ProviderError):
            await provider.embed([str(index) for index in range(39)])
        assert calls == 2
    finally:
        await provider.aclose()


async def test_qwen_embedding_rejects_dimension_drift_between_batches() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        size = len(json.loads(request.content)["input"])
        return _json({"data": [{"embedding": [1.0] * calls}] * size})

    config = dataclasses.replace(
        EMBEDDING_CONFIG, base_url="https://maas.example.com/compatible-mode/v1", dimension=None
    )
    provider = OpenAICompatibleProvider(config, transport=httpx.MockTransport(handler))
    try:
        with pytest.raises(ProviderUnavailableError, match="批次之间"):
            await provider.embed([str(index) for index in range(11)])
    finally:
        await provider.aclose()


async def test_embedding_rejects_dimension_change() -> None:
    """维度变了会让 Qdrant 拒绝写入：提前报出「换模型要重建集合」这种可操作的错。"""
    provider, _ = _provider(
        EMBEDDING_CONFIG, _json({"data": [{"embedding": [0.1, 0.2]}], "usage": {}})
    )

    with pytest.raises(ProviderUnavailableError, match="维度与配置不符"):
        await provider.embed(["第一篇"])
    await provider.aclose()


async def test_embedding_empty_input_is_empty_not_an_error() -> None:
    """空输入 → 空结果，**且不调上游**（契约测试 M6 定的口径）。

    这里原本断言「抛 `ProviderError`」，而 `fake` 对空输入返回空 —— 两个 provider 行为不一致。
    M6 的契约套件把这个分歧顶出来后统一为：**空输入是业务语义，不是协议错误**
    （「这批文章没有可嵌入的段落」不该表现成 500，也不该逼每个调用点补守卫）。
    原用例真正有价值的那半保留了：**不花一次白调用**。
    """
    provider, recorder = _provider(EMBEDDING_CONFIG, _json({"data": []}))

    response = await provider.embed([])

    assert response.vectors == []
    assert response.dimension == 0, "空批次报 0：不猜一个「看起来像嵌过了」的维度"
    assert recorder.requests == [], "空输入不该白花一次调用"
    await provider.aclose()


async def test_rerank_returns_index_and_score() -> None:
    provider, recorder = _provider(
        RERANK_CONFIG,
        _json(
            {
                "results": [
                    {"index": 2, "relevance_score": 0.91},
                    {"index": 0, "relevance_score": 0.4},
                ]
            }
        ),
    )

    response = await provider.rerank("星图", ["甲", "乙", "丙"], top_n=2)

    assert recorder.requests[0].url.path == "/v1/rerank"
    assert recorder.payloads[0] == {
        "model": "BAAI/bge-reranker-v2-m3",
        "query": "星图",
        "documents": ["甲", "乙", "丙"],
        "top_n": 2,
    }
    assert [(item.index, item.score) for item in response.results] == [(2, 0.91), (0, 0.4)]
    await provider.aclose()


async def test_rerank_rejects_out_of_range_index() -> None:
    """越界下标会让引用指向错误候选。"""
    provider, _ = _provider(
        RERANK_CONFIG, _json({"results": [{"index": 7, "relevance_score": 0.5}]})
    )

    with pytest.raises(ProviderUnavailableError, match="下标越界"):
        await provider.rerank("星图", ["甲", "乙"])
    await provider.aclose()


@pytest.mark.parametrize(
    ("base_url", "expected_path"),
    [
        ("https://maas.example.com/compatible-mode/v1", "/compatible-api/v1/reranks"),
        ("https://maas.example.com/compatible-mode/v1/", "/compatible-api/v1/reranks"),
        ("https://maas.example.com/compatible-api/v1", "/compatible-api/v1/reranks"),
        ("https://maas.example.com/proxy/compatible-mode/v1", "/proxy/compatible-api/v1/reranks"),
        ("https://maas.example.com:8443/compatible-mode/v1", "/compatible-api/v1/reranks"),
    ],
)
async def test_rerank_uses_qwen_compatible_endpoint_on_same_origin(
    base_url: str, expected_path: str
) -> None:
    """千问重排使用独立路径，密钥必须留在配置的同一主机与端口。"""
    config = dataclasses.replace(
        RERANK_CONFIG, base_url=base_url, model="qwen3.7-text-rerank"
    )
    provider, recorder = _provider(
        config, _json({"results": [{"index": 0, "relevance_score": 0.9}]})
    )
    try:
        response = await provider.rerank("星图", ["甲", "乙"], top_n=1)
        request = recorder.requests[0]
        configured = httpx.URL(base_url)
        assert request.url.path == expected_path
        assert (request.url.scheme, request.url.host, request.url.port) == (
            configured.scheme, configured.host, configured.port
        )
        assert recorder.payloads[0]["model"] == config.model
        assert request.headers["authorization"] == "Bearer sk-test-key"
        assert response.results[0].index == 0
        assert len(recorder.requests) == 1, "路径应在发请求前确定，不额外探测或重试其他端点"
    finally:
        await provider.aclose()


async def test_chat_keeps_qwen_chat_endpoint() -> None:
    config = dataclasses.replace(
        CHAT_CONFIG, base_url="https://maas.example.com/compatible-mode/v1"
    )
    provider, recorder = _provider(
        config, _json({"choices": [{"message": {"content": "ok"}}]})
    )
    try:
        await provider.chat([ChatMessage(MessageRole.USER, "你好")])
        assert recorder.requests[0].url.path == "/compatible-mode/v1/chat/completions"
    finally:
        await provider.aclose()


async def test_404_hints_at_endpoint_without_echoing_upstream_body() -> None:
    provider, recorder = _provider(
        RERANK_CONFIG, httpx.Response(404, text="private upstream detail sk-test-key")
    )
    try:
        with pytest.raises(ProviderError) as caught:
            await provider.rerank("星图", ["甲"])
        assert "接口路径" in (caught.value.detail or "")
        assert not caught.value.retryable
        assert "private upstream detail" not in str(caught.value.to_public_dict())
        assert "sk-test-key" not in str(caught.value.to_public_dict())
        assert len(recorder.requests) == 1
    finally:
        await provider.aclose()


@pytest.mark.parametrize(
    ("status", "expected", "retryable"),
    [
        (401, ProviderAuthError, False),
        (403, ProviderAuthError, False),
        (429, ProviderRateLimitError, True),
        (500, ProviderUnavailableError, True),
        (503, ProviderUnavailableError, True),
    ],
)
async def test_status_codes_map_to_classified_errors(
    status: int, expected: type[ProviderError], retryable: bool
) -> None:
    provider, _ = _provider(CHAT_CONFIG, httpx.Response(status, json={"error": "boom"}))

    with pytest.raises(expected) as excinfo:
        await provider.chat([ChatMessage(MessageRole.USER, "hi")])

    assert excinfo.value.retryable is retryable
    # 对外可见的错误体不能带密钥或上游原始报文
    public = excinfo.value.to_public_dict()
    assert "sk-test-key" not in str(public)
    assert "boom" not in str(public)
    await provider.aclose()


async def test_bad_request_is_not_retryable_and_hints_at_config() -> None:
    provider, _ = _provider(CHAT_CONFIG, httpx.Response(400, json={"error": "model not found"}))

    with pytest.raises(ProviderError) as excinfo:
        await provider.chat([ChatMessage(MessageRole.USER, "hi")])

    assert excinfo.value.retryable is False
    assert "模型名" in (excinfo.value.detail or "")
    await provider.aclose()


async def test_timeout_maps_to_retryable_timeout_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("too slow", request=request)

    # 关掉重试：这里验的是「超时 → 哪一类错误」，不关掉就要真等 5.6 秒退避
    provider = OpenAICompatibleProvider(
        CHAT_CONFIG, transport=httpx.MockTransport(handler), retry=RetryPolicy.disabled()
    )

    with pytest.raises(ProviderTimeoutError) as excinfo:
        await provider.chat([ChatMessage(MessageRole.USER, "hi")])

    assert excinfo.value.retryable is True
    await provider.aclose()


async def test_connection_failure_maps_to_unavailable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    provider = OpenAICompatibleProvider(
        CHAT_CONFIG, transport=httpx.MockTransport(handler), retry=RetryPolicy.disabled()
    )

    with pytest.raises(ProviderUnavailableError):
        await provider.chat([ChatMessage(MessageRole.USER, "hi")])
    await provider.aclose()


async def test_non_json_body_maps_to_unavailable() -> None:
    provider, _ = _provider(CHAT_CONFIG, httpx.Response(200, text="<html>gateway</html>"))

    with pytest.raises(ProviderUnavailableError, match="不是合法 JSON"):
        await provider.chat([ChatMessage(MessageRole.USER, "hi")])
    await provider.aclose()


async def test_missing_capability_fails_before_any_request() -> None:
    """用 chat 配置去调 embedding 必须当场报错，而不是发出一次注定失败的请求。"""
    provider, recorder = _provider(CHAT_CONFIG, _json({"data": []}))

    with pytest.raises(UnsupportedCapabilityError) as excinfo:
        await provider.embed(["文本"])

    assert excinfo.value.retryable is False
    assert "chat" in str(excinfo.value), "错误里要说明当前具备什么能力"
    assert recorder.requests == []
    await provider.aclose()
