"""用量按实际调用归属，缓存、缺失值和并发不得污染账目。"""

import asyncio

import httpx

from app.providers.embedding_cache import CachingEmbeddingModel, _VectorCache
from app.providers.fake import FakeProvider
from app.providers.models import ChatResponse, ProviderCapabilities, ProviderConfig, TokenUsage
from app.providers.openai_compatible import OpenAICompatibleProvider
from app.providers.usage import aggregate, collect_usage
from app.rag.agent import Agent, ToolBox, ToolResult, ToolSpec


async def test_agent_sums_every_step_including_budget_exhaustion() -> None:
    class Chat:
        async def chat(self, messages, **kwargs):
            return ChatResponse(
                '{"tool":"search","arguments":{}}',
                TokenUsage(prompt_tokens=10, completion_tokens=3, total_tokens=13),
            )

    async def search(arguments):
        return ToolResult(summary="查询结果")

    run = await Agent(chat=Chat(), tools=ToolBox([ToolSpec("search", "检索", search)])).run("问题")
    assert run.usage is not None
    assert run.usage.total_tokens == 52
    assert run.usage.usage_complete is True
    assert len(run.usage.model_calls or []) == 4
    assert run.stop_reason == "length"


async def test_embedding_cache_hit_is_not_another_model_call() -> None:
    model = CachingEmbeddingModel(FakeProvider(), cache=_VectorCache(8))
    with collect_usage() as calls:
        await model.embed(["同一个问题"])
        await model.embed(["同一个问题"])
        assert len(calls) == 1


async def test_missing_upstream_usage_is_unknown_not_free() -> None:
    model = OpenAICompatibleProvider(
        ProviderConfig(
            role="chat",
            provider="openai_compatible",
            base_url="https://example.invalid",
            model="m",
            capabilities=ProviderCapabilities(chat=True),
        ),
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                json={
                    "choices": [{"message": {"content": "回答"}, "finish_reason": "stop"}],
                },
            )
        ),
    )
    try:
        with collect_usage() as calls:
            await model.chat([])
            usage = aggregate(calls)
            assert usage.usage_complete is False
            assert calls[0].total_tokens is None
    finally:
        await model.aclose()


async def test_concurrent_requests_do_not_share_usage() -> None:
    async def run(text):
        with collect_usage() as calls:
            await asyncio.sleep(0)
            await FakeProvider().embed([text])
            await asyncio.sleep(0)
            return aggregate(calls)

    first, second = await asyncio.gather(run("短"), run("长" * 100))
    assert len(first.model_calls or []) == len(second.model_calls or []) == 1
    assert first.total_tokens < second.total_tokens


async def test_cached_query_keeps_effective_model_identity() -> None:
    from app.rag.pipeline import IndexedChunk, RetrievalConfig, RetrievalPipeline
    from app.rag.qa import QaService
    from app.schemas.qa import QaStreamRequest

    model = CachingEmbeddingModel(FakeProvider(), cache=_VectorCache(8))
    chunk = IndexedChunk(chunk_id="c1", post_id=1, title="写作", text="写作方法", payload={})
    service = QaService(
        pipeline=RetrievalPipeline(corpus=[chunk], config=RetrievalConfig(), embedder=model),
        chat=FakeProvider(),
    )
    first = await service.answer(QaStreamRequest(question="写作"))
    second = await service.answer(QaStreamRequest(question="写作"))
    assert first.usage.model_fingerprints == second.usage.model_fingerprints
    assert "embedding" in (second.usage.model_fingerprints or {})
    assert len(first.usage.model_calls or []) > len(second.usage.model_calls or [])


async def test_split_embedding_batches_are_separate_calls() -> None:
    def respond(request):
        import json

        texts = json.loads(request.content)["input"]
        return httpx.Response(
            200,
            json={
                "data": [{"embedding": [1.0, 0.0]} for _ in texts],
                "usage": {"prompt_tokens": len(texts)},
            },
        )

    model = OpenAICompatibleProvider(
        ProviderConfig(
            role="embedding",
            provider="openai_compatible",
            base_url="https://example.invalid/compatible-mode/v1",
            model="e",
            capabilities=ProviderCapabilities(embedding=True),
        ),
        transport=httpx.MockTransport(respond),
    )
    try:
        with collect_usage() as calls:
            await model.embed(["文字"] * 23)
            assert [call.prompt_tokens for call in calls] == [10, 10, 3]
            assert aggregate(calls).total_tokens == 23
    finally:
        await model.aclose()


async def test_price_snapshot_uses_actual_config_and_survives_price_change() -> None:
    from dataclasses import replace

    from app.providers.config_source import configs_from_rows
    from app.providers.resolver import ProviderResolver

    first = configs_from_rows(
        [
            dict(
                id=17,
                user_id=7,
                role="chat",
                provider="fake",
                model="personal",
                base_url="",
                price_input_per_million="3.0000",
                price_output_per_million="4.0000",
            )
        ]
    )[0]
    source = [first]
    resolver = ProviderResolver(source=lambda: source)
    with collect_usage() as calls:
        await resolver.registry().chat_model().chat([])
        source[0] = replace(first, price_input="9.0000")
        await resolver.registry().chat_model().chat([])
        assert [call.price_input for call in calls] == ["3.0000", "9.0000"]
        assert all(call.config_user_id == 7 and call.config_id == 17 for call in calls)
        assert first.fingerprint() == source[0].fingerprint(), "单价变化不得误报向量空间变化"
