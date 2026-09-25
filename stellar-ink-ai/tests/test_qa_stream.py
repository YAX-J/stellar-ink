"""流式问答编排测试：事件顺序、引用先于增量、取消与降级。

这一层最容易出的不是「功能不对」而是**顺序不对**：前端按事件流增量渲染，
一旦 `citation` 落在 `done` 之后，引用就会在答案已经显示完之后才冒出来；
而 `done` 缺失会让前端永远停在「生成中」。因此下面把顺序当契约来断言。
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import pytest

from app.providers.fake import FakeProvider
from app.providers.models import (
    ChatMessage,
    ChatResponse,
    ChatStreamChunk,
    ProviderCapabilities,
    ProviderConfig,
    TokenUsage,
)
from app.rag.pipeline import IndexedChunk, RetrievalConfig, RetrievalPipeline
from app.rag.qa import QaService, QaSettings
from app.schemas.common import DoneReason
from app.schemas.qa import QaStreamRequest
from app.schemas.qa_stream import EVENT_CITATION, EVENT_DELTA, EVENT_DONE, EVENT_META

QUESTION = "一年写十八万字的方法是什么？"


def build_pipeline(*, refuse_everything: bool = False) -> RetrievalPipeline:
    """两篇小语料：一篇相关、一篇无关，够驱动「有依据 / 无依据」两条路径。

    `refuse_everything=True` 时抬到「不可能达到的 BM25 绝对下限」，并**只留 sparse 一路**：
    `min_score` 是 Sparse 的门限，Dense 那一路不认它 —— 两路都开时，sparse 被清空、
    dense 仍会贡献候选，整体就不会拒答（这正是「混合检索更难拒答」的原因，
    要挡语义相近但无答案的问题得靠 `min_dense_score`）。
    另外 `min_score_ratio` 永远留最高分，拿它当拒答开关会让拒答率恒为 0（见 fast-track-plan §5）。
    """
    corpus = [
        IndexedChunk(
            chunk_id="c1",
            post_id=1,
            title="写作的复利",
            text="写作的复利\n一年写十八万字靠的是每天五百字，不是等灵感。",
            payload={"text": "一年写十八万字靠的是每天五百字，不是等灵感。", "chunkIndex": 0},
        ),
        IndexedChunk(
            chunk_id="c2",
            post_id=2,
            title="深夜的星图",
            text="深夜的星图\n我把文章比作星辰，因为它们都在很远的地方发光。",
            payload={"text": "我把文章比作星辰，因为它们都在很远的地方发光。", "chunkIndex": 0},
        ),
    ]
    config = (
        RetrievalConfig(enable_dense=False, min_score=10_000.0)
        if refuse_everything
        else RetrievalConfig()
    )
    return RetrievalPipeline(corpus=corpus, config=config, embedder=FakeProvider())


class ChunkedChat:
    """会分块吐字的流式桩：用来证明 `delta` 真的是增量的，而不是把整段切碎再发一次。"""

    def __init__(self, pieces: list[str], *, usage: TokenUsage | None = None) -> None:
        self._pieces = pieces
        self._usage = usage
        self.calls = 0
        self.closed = False

    async def stream_chat(
        self,
        messages: list[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[ChatStreamChunk]:
        self.calls += 1
        for index, piece in enumerate(self._pieces):
            # 模拟「消费者中途不再取值」：编排层必须能原样停下，而不是继续烧 token
            if self.closed:
                return
            last = index == len(self._pieces) - 1
            yield ChatStreamChunk(
                text=piece,
                finish_reason="stop" if last else None,
                usage=self._usage if last else None,
            )

    async def chat(
        self,
        messages: list[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> ChatResponse:
        raise AssertionError("声明了 stream_chat 就应当走流式路径，不该回落到一次性调用")


async def collect(service: QaService, question: str = QUESTION) -> list:
    return [event async for event in service.stream(QaStreamRequest(question=question))]


async def test_event_order_is_meta_citation_delta_done() -> None:
    chat = ChunkedChat(["每天五百字。", "不是等灵感。"])
    service = QaService(pipeline=build_pipeline(), chat=chat)  # type: ignore[arg-type]

    events = await collect(service)
    types = [event.type for event in events]

    assert types[0] == EVENT_META, "meta 必须先到：前端要立刻知道请求被受理了"
    assert types[-1] == EVENT_DONE, "没有 done 前端会永远停在「生成中」"
    assert EVENT_CITATION in types and EVENT_DELTA in types
    assert types.index(EVENT_CITATION) < types.index(EVENT_DELTA), "引用由检索决定，不必等模型"
    assert types.count(EVENT_DONE) == 1


async def test_deltas_are_incremental_and_joined_in_done() -> None:
    chat = ChunkedChat(["每天五百字。", "不是等灵感。"])
    service = QaService(pipeline=build_pipeline(), chat=chat)  # type: ignore[arg-type]

    events = await collect(service)
    pieces = [event.payload["text"] for event in events if event.type == EVENT_DELTA]
    final = next(event for event in events if event.type == EVENT_DONE)

    assert pieces == ["每天五百字。", "不是等灵感。"], "增量必须原样透出，不要合并成一块"
    assert final.payload["answer"] == "每天五百字。不是等灵感。"
    assert final.payload["doneReason"] == DoneReason.STOP.value
    assert final.payload["evidenceSufficient"] is True


async def test_streamed_usage_reports_the_model_name_not_unknown() -> None:
    """流式的 `meta.model` 与 `done.usage.model` 都要是真模型名。

    旧代码用 `getattr(chat, "model")` 取值，而真实 Provider 的模型名在 `config.model` 上 ——
    于是接上真模型后 `meta.model` 一直是 `unknown`：链路完全正常，却看起来像「没接上模型」。
    """

    class ConfiguredChat(ChunkedChat):
        """像真实 Provider 一样把配置挂在 `config` 上（没有 `model` 属性）。"""

        config = ProviderConfig(
            role="chat",
            provider="openai_compatible",
            base_url="http://model.invalid/v1",
            model="deepseek-flash",
            capabilities=ProviderCapabilities(chat=True),
        )

    chat = ConfiguredChat(["答。"], usage=TokenUsage.of(10, 2, latency_ms=0, model=None))
    service = QaService(pipeline=build_pipeline(), chat=chat)  # type: ignore[arg-type]

    events = await collect(service)
    meta = next(event for event in events if event.type == EVENT_META)
    final = next(event for event in events if event.type == EVENT_DONE)

    assert meta.payload["model"] == "deepseek-flash", "SSE 的 meta 要说清是哪个模型在答"
    assert final.payload["usage"]["model"] == "deepseek-flash"


async def test_streamed_latency_covers_generation_not_just_retrieval() -> None:
    """单块的 `usage.latency_ms` 恒为 0（那不是一个回答的耗时）：

    照抄它会让跑了十几秒的流式回答在响应里显示成几毫秒，
    而前端与运维恰恰用这个数字判断「这条链路快不快」。
    这里让桩真的慢 30ms，断言报告出来的耗时**确实包含了生成时间**。
    """

    class SlowChat(ChunkedChat):
        async def stream_chat(self, messages, *, temperature=None, max_tokens=None):
            await asyncio.sleep(0.03)
            async for chunk in super().stream_chat(
                messages, temperature=temperature, max_tokens=max_tokens
            ):
                yield chunk

    chat = SlowChat(
        ["慢", "地", "写"],
        usage=TokenUsage.of(505, 1636, latency_ms=0, model="deepseek-flash"),
    )
    service = QaService(pipeline=build_pipeline(), chat=chat)  # type: ignore[arg-type]

    events = await collect(service)
    final = next(event for event in events if event.type == EVENT_DONE)

    assert final.payload["usage"]["latencyMs"] >= 25, "生成耗时必须计进去（旧代码只报检索那几毫秒）"
    assert final.payload["usage"]["completionTokens"] == 1636, "用量要原样透出"


async def test_stream_falls_back_to_one_shot_when_model_cannot_stream() -> None:
    """FakeProvider 没有 stream_chat：内容必须完整，只是少了几次增量。"""
    service = QaService(pipeline=build_pipeline(), chat=FakeProvider(), settings=QaSettings())

    events = await collect(service)
    deltas = [event for event in events if event.type == EVENT_DELTA]
    final = next(event for event in events if event.type == EVENT_DONE)

    assert len(deltas) == 1, "回落路径应当只吐一个 delta"
    assert deltas[0].payload["text"] == final.payload["answer"]
    assert final.payload["usage"]["model"] == "fake"


async def test_no_evidence_refuses_without_calling_the_model() -> None:
    chat = ChunkedChat(["不该出现"])
    service = QaService(
        pipeline=build_pipeline(refuse_everything=True),
        chat=chat,  # type: ignore[arg-type]
        settings=QaSettings(refusal_message="没有依据"),
    )

    events = await collect(service, question="怎么养一只会写诗的猫？")
    types = [event.type for event in events]
    final = next(event for event in events if event.type == EVENT_DONE)

    assert types == [EVENT_META, EVENT_DELTA, EVENT_DONE], "拒答路径不该有引用，也不该调模型"
    assert chat.calls == 0, "没有候选还去叫模型，是白花钱"
    assert final.payload["evidenceSufficient"] is False
    assert final.payload["doneReason"] == DoneReason.REFUSED.value
    assert final.payload["answer"] == "没有依据"
    # 拒答路径根本没叫模型，所以 model 如实为 None —— 不该为了「填满字段」编一个模型名
    assert final.payload["usage"]["model"] is None


async def test_model_self_refusal_keeps_citations() -> None:
    """模型说答不了、但检索确实找到了段落：两种情况对用户是不同的信息，不能一起丢掉。"""
    chat = ChunkedChat([""], usage=TokenUsage(prompt_tokens=10, model="stub"))
    service = QaService(pipeline=build_pipeline(), chat=chat)  # type: ignore[arg-type]

    events = await collect(service)
    types = [event.type for event in events]
    final = next(event for event in events if event.type == EVENT_DONE)

    assert EVENT_CITATION in types, "空输出不代表没有依据：引用要保留"
    assert final.payload["evidenceSufficient"] is False
    assert final.payload["doneReason"] == DoneReason.REFUSED.value
    assert final.payload["answer"], "拒答也要有文案，不能是空字符串"


async def test_usage_joins_retrieval_and_model_latency() -> None:
    chat = ChunkedChat(
        ["好。"],
        usage=TokenUsage(prompt_tokens=7, completion_tokens=2, latency_ms=30, model="stub"),
    )
    service = QaService(pipeline=build_pipeline(), chat=chat)  # type: ignore[arg-type]

    events = await collect(service)
    final = next(event for event in events if event.type == EVENT_DONE)
    usage = final.payload["usage"]

    assert usage["promptTokens"] == 7 and usage["completionTokens"] == 2
    assert usage["model"] == "stub"
    assert usage["latencyMs"] >= 30, "端到端耗时不能小于模型自身耗时（漏掉检索段会低估用户等待）"


async def test_consumer_can_stop_early_without_extra_model_calls() -> None:
    """取消传播的上游一半：调用方 `aclose()` 之后，编排层不该再让模型继续生成。"""
    chat = ChunkedChat(["第一段。", "第二段。", "第三段。"])
    service = QaService(pipeline=build_pipeline(), chat=chat)  # type: ignore[arg-type]

    stream = service.stream(QaStreamRequest(question=QUESTION))
    seen = []
    async for event in stream:
        seen.append(event.type)
        if event.type == EVENT_DELTA:
            break
    await stream.aclose()  # type: ignore[attr-defined]
    chat.closed = True

    assert seen[-1] == EVENT_DELTA, "确认是在增量中途停下的"
    assert chat.calls == 1


@pytest.mark.parametrize("top_k", [1, 5])
async def test_top_k_is_passed_through_to_retrieval(top_k: int) -> None:
    chat = ChunkedChat(["好。"], usage=TokenUsage(model="stub"))
    service = QaService(pipeline=build_pipeline(), chat=chat)  # type: ignore[arg-type]

    events = [
        event async for event in service.stream(QaStreamRequest(question=QUESTION, top_k=top_k))
    ]
    meta = events[0]

    assert meta.payload["topK"] == top_k
    assert meta.payload["model"] == "unknown", "桩没有 MODEL_TAG，标识要如实回退而不是编一个"
    assert meta.payload["questionLength"] == len(QUESTION)
    assert QUESTION not in str(meta.payload), (
        "meta 不回显问题原文（减少用户输入在日志/抓包里的留存）"
    )
