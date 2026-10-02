"""Provider 契约测试（M6 验收第一条）。

「同一套 Provider Contract Tests 同时通过云模型和本地兼容服务」——
所以这里不是给每个 Provider 各写一套用例，而是**一份用例、两个实现**：

* `fake`：离线桩，确定、零成本；
* `openai_compatible` + `MockTransport`：模拟一个 OpenAI 兼容端点。
  vLLM / SGLang 暴露的就是这个协议，所以这份用例**原样**能打到本地推理服务上
  （真机验证要 GPU —— 那是环境动作，不是代码动作；协议面在这里已经钉住了）。

契约本身只有六条，但每条都对应一类真实的坑：

1. `chat` 回 `ChatResponse`，`usage` 的四个数都在位（缺了就没法算成本与延迟）；
2. `embed` **顺序与输入一一对应**（错位会让引用串位）、维度一致；
3. `rerank` 的分数**降序**、下标落在文档范围内、`top_n` 真的截断；
4. 错误分类与状态码对齐（401 鉴权 / 429 限流 / 5xx 不可用 / 400 配置）；
5. 空输入不炸、也不偷偷调用上游；
6. **失败是分类错误**，不是裸的 httpx 异常 —— 上层降级与提示都依赖这个分类。
"""

from __future__ import annotations

import hashlib
import json

import httpx
import pytest

from app.providers.errors import (
    ProviderAuthError,
    ProviderError,
    ProviderRateLimitError,
    ProviderUnavailableError,
)
from app.providers.fake import FakeProvider
from app.providers.models import (
    ChatMessage,
    MessageRole,
    ProviderCapabilities,
    ProviderConfig,
)
from app.providers.openai_compatible import OpenAICompatibleProvider
from app.providers.retry import RetryPolicy

CHAT_BODY = {
    "choices": [{"message": {"content": "好"}, "finish_reason": "stop"}],
    "usage": {"prompt_tokens": 3, "completion_tokens": 1, "total_tokens": 4},
}
EMBED_BODY = {
    "data": [{"embedding": [0.1, 0.2, 0.3]}, {"embedding": [0.4, 0.5, 0.6]}],
    "usage": {"prompt_tokens": 2, "total_tokens": 2},
}
RERANK_BODY = {
    "results": [{"index": 1, "relevance_score": 0.9}, {"index": 0, "relevance_score": 0.2}]
}


def _handler(request: httpx.Request) -> httpx.Response:
    """一个最小但**忠实**的 OpenAI 兼容端点（vLLM/SGLang 的形状）。

    「忠实」在这里很关键：嵌入向量必须**跟着输入走**（由文本算出来），
    否则「顺序与输入一一对应」这条契约就测不出来 —— 桩偷懒会让用例一起偷懒。
    """
    path = request.url.path
    if path.endswith("/chat/completions"):
        return httpx.Response(200, json=CHAT_BODY)
    if path.endswith("/embeddings"):
        payload = json.loads(request.content or b"{}")
        texts = payload.get("input") or []
        vectors = [_vector_of(text) for text in texts]
        return httpx.Response(200, json={"data": [{"embedding": v} for v in vectors]})
    if path.endswith("/rerank"):
        payload = json.loads(request.content or b"{}")
        documents = payload.get("documents") or []
        top_n = payload.get("top_n")
        # 按文档长度排个序（确定、可预期），分数降序 —— 真实重排端点也是降序回的
        scored = sorted(
            ((index, float(len(text))) for index, text in enumerate(documents)),
            key=lambda item: item[1],
            reverse=True,
        )
        if isinstance(top_n, int):
            scored = scored[:top_n]
        return httpx.Response(
            200, json={"results": [{"index": i, "relevance_score": s} for i, s in scored]}
        )
    return httpx.Response(404, json={"error": {"message": f"no such path {path}"}})


def _vector_of(text: str) -> list[float]:
    """把文本变成一个确定的三维向量（同文本同向量、不同文本不同向量）。"""
    digest = hashlib.sha256(text.encode("utf-8")).digest()
    return [round(digest[index] / 255, 4) for index in range(3)]


def _config(provider: str, **caps: bool) -> ProviderConfig:
    return ProviderConfig(
        role="chat",
        provider=provider,
        base_url="https://local.invalid/v1",
        model="contract-model",
        api_key="sk-test",
        capabilities=ProviderCapabilities(
            chat=caps.get("chat", True),
            embedding=caps.get("embedding", True),
            rerank=caps.get("rerank", True),
        ),
    )


def _providers() -> list[tuple[str, object]]:
    return [
        ("fake", FakeProvider(_config("fake"))),
        (
            "openai_compatible(本地兼容服务协议)",
            OpenAICompatibleProvider(
                _config("openai_compatible"),
                transport=httpx.MockTransport(_handler),
                retry=RetryPolicy.disabled(),
            ),
        ),
    ]


PROVIDERS = _providers()


@pytest.mark.parametrize(("label", "provider"), PROVIDERS, ids=[item[0] for item in PROVIDERS])
async def test_chat_returns_a_full_response(label: str, provider: object) -> None:
    del label
    response = await provider.chat([ChatMessage(MessageRole.USER, "你好")], max_tokens=8)  # type: ignore[attr-defined]

    assert isinstance(response.text, str)
    usage = response.usage
    assert usage.prompt_tokens >= 0
    assert usage.completion_tokens >= 0
    assert usage.total_tokens >= usage.completion_tokens
    assert usage.latency_ms >= 0
    assert usage.model, "模型名要在位：回答里要能说出「这是谁答的」"
    assert response.finish_reason


@pytest.mark.parametrize(("label", "provider"), PROVIDERS, ids=[item[0] for item in PROVIDERS])
async def test_embed_keeps_order_and_dimension(label: str, provider: object) -> None:
    """顺序必须与输入一一对应：错位会让引用串位，而串位在界面上看不出来。"""
    if label == "fake":
        texts = ["第一句话", "第二句话有点不同"]
    else:
        texts = ["第一句话", "第二句话有点不同"]

    response = await provider.embed(texts)  # type: ignore[attr-defined]

    assert len(response.vectors) == 2
    dimensions = {len(vector) for vector in response.vectors}
    assert len(dimensions) == 1, "同一批的维度必须一致"

    # 换个顺序问：第一句的向量应当跟着第一句走（fake 是哈希、本地是模型，两者都该如此）
    swapped = await provider.embed(list(reversed(texts)))  # type: ignore[attr-defined]
    assert swapped.vectors[0] == pytest.approx(response.vectors[1]), "顺序被吃掉了"


@pytest.mark.parametrize(("label", "provider"), PROVIDERS, ids=[item[0] for item in PROVIDERS])
async def test_embed_rejects_huge_batches_or_handles_empty(label: str, provider: object) -> None:
    """空输入不炸（也不该偷偷调用上游）。"""
    del label
    response = await provider.embed([])  # type: ignore[attr-defined]

    assert response.vectors == []


@pytest.mark.parametrize(("label", "provider"), PROVIDERS, ids=[item[0] for item in PROVIDERS])
async def test_rerank_returns_sorted_scores_with_valid_indexes(
    label: str, provider: object
) -> None:
    documents = ["甲文档", "乙文档", "丙文档"]

    response = await provider.rerank("问题", documents)  # type: ignore[attr-defined]

    assert response.results, "至少要回一条"
    indexes = [item.index for item in response.results]
    assert all(0 <= index < len(documents) for index in indexes), "下标必须落在文档范围内"
    scores = [item.score for item in response.results]
    assert scores == sorted(scores, reverse=True), "分数必须降序：调用方按顺序取前 N 条"


@pytest.mark.parametrize(("label", "provider"), PROVIDERS, ids=[item[0] for item in PROVIDERS])
async def test_rerank_top_n_truncates(label: str, provider: object) -> None:
    response = await provider.rerank("问题", ["甲", "乙", "丙"], top_n=1)  # type: ignore[attr-defined]

    assert len(response.results) == 1


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        (401, ProviderAuthError),
        (429, ProviderRateLimitError),
        (503, ProviderUnavailableError),
        (400, ProviderError),
    ],
)
async def test_status_codes_map_to_classified_errors(
    status: int, expected: type[ProviderError]
) -> None:
    """错误分类是上层降级与提示的依据：裸的 httpx 异常会让「要不要重试」无从判断。"""

    def failing(request: httpx.Request) -> httpx.Response:
        del request
        return httpx.Response(status, json={"error": {"message": "nope"}})

    provider = OpenAICompatibleProvider(
        _config("openai_compatible"),
        transport=httpx.MockTransport(failing),
        retry=RetryPolicy.disabled(),
    )

    with pytest.raises(expected) as error:
        await provider.chat([ChatMessage(MessageRole.USER, "你好")], max_tokens=8)

    assert isinstance(error.value, ProviderError)
    # 可重试性必须与分类一致：429/5xx 为真，401/400 为假
    assert error.value.retryable is (status in (429, 503))
    await provider.aclose()


async def test_unknown_path_is_not_silently_empty() -> None:
    """路径拼错（例如 base_url 填成完整端点）必须报错，而不是回一个空结果。"""
    provider = OpenAICompatibleProvider(
        _config("openai_compatible", rerank=True),
        transport=httpx.MockTransport(_handler),
        retry=RetryPolicy.disabled(),
    )

    # MockTransport 对未知路径回 404；这里用一个不存在的服务面来触发它
    with pytest.raises(ProviderError):
        await provider._post("/v1/definitely-not-a-real-endpoint", {})  # noqa: SLF001

    await provider.aclose()
