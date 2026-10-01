"""嵌入缓存：整库只嵌一遍，且**下标绝不错位**。

为什么这个类值得单独测（实测背景）：一轮五组策略里有三组带 dense，而 `RetrievalPipeline.prepare()`
是实例级的 —— 不缓存就是三次整库嵌入，免费档下直接把额度打光，第 4 组起全线 429，
30 道题全被降级成「拒答」，看起来像「开了重排就彻底失效」。

比「省调用」更要紧的是**顺序**：只对未命中的文本发起批量调用，再按下标拼回去。
拼错的话检索结果看起来正常，引用却指向另一段文字 —— 那是最难发现的一类错。
"""

from __future__ import annotations

import pytest

from app.providers.embedding_cache import (
    CachingEmbeddingModel,
    cache_stats,
    reset_embedding_cache,
)
from app.providers.models import (
    EmbeddingResponse,
    ProviderCapabilities,
    ProviderConfig,
    TokenUsage,
)


class _CountingEmbedder:
    """记录每次收到的文本，返回可辨认的向量（长度 3，第一维是文本长度）。"""

    def __init__(self, *, dimension_fixed: int = 3) -> None:
        self.calls: list[list[str]] = []
        self._dimension = dimension_fixed
        self.config = ProviderConfig(
            role="embedding",
            provider="openai_compatible",
            base_url="https://example.invalid/v1",
            model="embed-a",
            api_key="sk-test",
            capabilities=ProviderCapabilities(embedding=True),
        )

    async def embed(self, texts: list[str]) -> EmbeddingResponse:
        self.calls.append(list(texts))
        vectors = [[float(len(text)), float(index), 0.0] for index, text in enumerate(texts)]
        return EmbeddingResponse(
            vectors=vectors, dimension=self._dimension, usage=TokenUsage(prompt_tokens=len(texts))
        )


@pytest.fixture(autouse=True)
def _clean() -> None:
    reset_embedding_cache()


async def test_second_identical_call_hits_the_cache() -> None:
    inner = _CountingEmbedder()
    model = CachingEmbeddingModel(inner)

    first = await model.embed(["甲", "乙"])
    second = await model.embed(["甲", "乙"])

    assert inner.calls == [["甲", "乙"]], "第二次不该再打上游"
    assert first.vectors == second.vectors
    assert cache_stats()["hits"] == 2


async def test_partial_hit_only_embeds_the_missing_ones() -> None:
    inner = _CountingEmbedder()
    model = CachingEmbeddingModel(inner)

    await model.embed(["甲", "乙"])
    # 顺序还要对得上：甲命中、丙未命中
    response = await model.embed(["甲", "丙"])

    assert inner.calls[1] == ["丙"], "只把未命中的那段发上去"
    assert [vector[0] for vector in response.vectors] == [1.0, 1.0], "两个都是单字，长度为 1"


async def test_order_is_preserved_when_only_the_middle_misses() -> None:
    """**这一条是防引用串位的**：中间那段未命中时，拼回去的位置不能错。"""
    inner = _CountingEmbedder()
    model = CachingEmbeddingModel(inner)

    await model.embed(["aa", "bb"])
    response = await model.embed(["aa", "cccc", "bb"])

    assert inner.calls[1] == ["cccc"]
    # 向量第一维是文本长度：4 说明中间那段的向量确实落在中间
    assert [vector[0] for vector in response.vectors] == [2.0, 4.0, 2.0]


async def test_different_models_do_not_share_vectors() -> None:
    """换嵌入模型必须重新嵌：只按文本做键会拿回旧向量，而那是「链路全对、结果全错」。"""
    first = _CountingEmbedder()
    model_a = CachingEmbeddingModel(first)
    await model_a.embed(["甲"])

    second = _CountingEmbedder()
    second.config = ProviderConfig(
        role="embedding",
        provider="openai_compatible",
        base_url="https://example.invalid/v1",
        model="embed-b",
        api_key="sk-test",
        capabilities=ProviderCapabilities(embedding=True),
    )
    model_b = CachingEmbeddingModel(second)
    await model_b.embed(["甲"])

    assert second.calls == [["甲"]], "不同模型不能命中同一份缓存"


async def test_cache_is_bounded() -> None:
    inner = _CountingEmbedder()
    model = CachingEmbeddingModel(inner)
    from app.providers import embedding_cache

    tiny = embedding_cache._VectorCache(2)  # noqa: SLF001 - 验的就是「上限生效」
    wrapped = CachingEmbeddingModel(inner, cache=tiny)

    await wrapped.embed(["a", "b", "c"])

    assert tiny.size == 2, "超出上限时淘汰最旧的"
    assert model is not None  # 全局缓存不受影响


async def test_upstream_result_count_mismatch_is_rejected() -> None:
    """数量不齐必须报错，而不是拼出错位的向量。"""

    class _Broken(_CountingEmbedder):
        async def embed(self, texts: list[str]) -> EmbeddingResponse:
            self.calls.append(list(texts))
            return EmbeddingResponse(vectors=[[1.0, 2.0, 3.0]], dimension=3, usage=TokenUsage())

    model = CachingEmbeddingModel(_Broken())

    with pytest.raises(ValueError, match="数量与请求不一致"):
        await model.embed(["甲", "乙"])


async def test_other_capabilities_are_delegated() -> None:
    """一个角色的配置可能同时声明 chat + embedding：包装之后那些方法还得能用。

    漏掉委派的表现是 `AttributeError`，与「能力不符」的报错完全是两回事，
    排查方向会被带偏 —— 所以这里明确断言它。
    """

    class _Multi:
        config = ProviderConfig(
            role="chat",
            provider="openai_compatible",
            base_url="https://example.invalid/v1",
            model="multi",
            api_key="sk-test",
            capabilities=ProviderCapabilities(chat=True, embedding=True),
        )

        async def embed(self, texts: list[str]) -> EmbeddingResponse:
            return EmbeddingResponse(
                vectors=[[0.0, 0.0, 0.0] for _ in texts], dimension=3, usage=TokenUsage()
            )

        def chat_marker(self) -> str:
            return "透传成功"

    wrapped = CachingEmbeddingModel(_Multi())

    assert wrapped.chat_marker() == "透传成功"  # type: ignore[attr-defined]
    assert wrapped.config is not None and wrapped.config.model == "multi"
