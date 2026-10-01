"""trace 回放的契约测试：**两侧读同一份 fixture**。

Java 侧（`AiContractTest`）拿它验 `AiTraceDTO` 能反序列化；这里验「真的跑一遍之后，
事件的键名与样例完全一致」。少了这一半，样例就只是「我们以为的形状」——
而 Java 那边的反序列化测试会因为字段名对不上而红，却指向不到真正的改动点。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx
import pytest

from app.core.trace import bind_trace, reset_traces, trace_snapshot
from app.providers.fake import FakeProvider
from app.providers.models import ChatMessage, MessageRole, ProviderCapabilities, ProviderConfig
from app.providers.openai_compatible import OpenAICompatibleProvider
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline, build_corpus
from scripts.seed_posts import load_seed_posts

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "trace_replay_response.json"
TRACE_ID = "0123456789abcdef0123456789abcdef"


def _fixture() -> dict[str, Any]:
    with FIXTURE.open(encoding="utf-8") as handle:
        return json.load(handle)


async def _run_once() -> dict[str, Any]:
    """与 `scripts/gen_trace_fixture.py` 同一套动作（真的跑，只把时间字段差别忽略掉）。"""
    reset_traces()
    pipeline = RetrievalPipeline(
        corpus=build_corpus(load_seed_posts()),
        config=RetrievalConfig(enable_sparse=True, enable_dense=True, label="contract"),
        embedder=FakeProvider(),
    )

    def handler(request: httpx.Request) -> httpx.Response:
        del request
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": "因为每篇文章都对应夜空中的一个坐标。"}}],
                "usage": {"prompt_tokens": 49, "completion_tokens": 51},
            },
        )

    provider = OpenAICompatibleProvider(
        ProviderConfig(
            role="chat",
            provider="openai_compatible",
            base_url="https://example.invalid/v1",
            model="fixture-chat",
            api_key="sk-fixture",
            capabilities=ProviderCapabilities(chat=True),
        ),
        transport=httpx.MockTransport(handler),
    )
    with bind_trace(TRACE_ID):
        await pipeline.retrieve("星笺为什么把文章比作星辰？", top_k=3)
        await provider.chat([ChatMessage(MessageRole.USER, "星笺为什么把文章比作星辰？")])
        await provider.aclose()
        snapshot = trace_snapshot(TRACE_ID)
    assert snapshot is not None
    return snapshot


def test_fixture_covers_both_legs() -> None:
    """样例必须覆盖检索与模型两段；只有一段的样例守不住另一段的键名。"""
    kinds = {event["kind"] for event in _fixture()["events"]}

    assert kinds == {"retrieval", "model"}


def test_fixture_is_deterministic() -> None:
    """时间字段被归一化过：否则 fixture 每次生成都不同，契约测试会以噪声的形式红。"""
    for event in _fixture()["events"]:
        assert "latencyMs" not in event
        assert isinstance(event["atMs"], int)


@pytest.mark.asyncio
async def test_live_events_match_the_fixture_keys() -> None:
    expected = _fixture()
    live = await _run_once()

    assert live["traceId"] == expected["traceId"]
    by_kind_expected = {event["kind"]: set(event) for event in expected["events"]}
    by_kind_live = {event["kind"]: set(event) for event in live["events"]}

    assert by_kind_live.keys() == by_kind_expected.keys()
    for kind, keys in by_kind_expected.items():
        # 差别只允许出现在「会随时间变化的字段」上，其余键名必须一致
        assert by_kind_live[kind] - {"atMs", "latencyMs"} == keys - {"atMs"}, (
            f"{kind} 事件的键名与契约样例不一致："
            f"多 {sorted(by_kind_live[kind] - keys)}、少 {sorted(keys - by_kind_live[kind])}"
        )
