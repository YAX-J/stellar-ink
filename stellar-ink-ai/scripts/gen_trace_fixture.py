"""生成「按 traceId 回放」的契约样例（Java 与 Python 两侧共读）。

为什么要脚本生成而不是手写：手写的样例只证明「我以为长这样」。
这里**真的跑一次检索**（用 FakeProvider，不需要密钥与网络），再把结果里**会随时间变化的字段**
（`atMs` / `latencyMs`）归一化 —— 不归一化的话 fixture 每次生成都不同，
契约测试就会以「提示词变了一行」的形态红，而它想守的是**键名与类型**。

用法：``uv run python scripts/gen_trace_fixture.py``
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

import httpx

from app.core.trace import bind_trace, reset_traces, trace_snapshot
from app.providers.fake import FakeProvider
from app.providers.models import (
    ChatMessage,
    MessageRole,
    ProviderCapabilities,
    ProviderConfig,
)
from app.providers.openai_compatible import OpenAICompatibleProvider
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline, build_corpus

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console
from scripts.seed_posts import load_seed_posts

FIXTURE = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "trace_replay_response.json"

#: 固定 traceId：样例要能一眼看出「这就是那条链路」，随机值会让每次 diff 都不同
TRACE_ID = "0123456789abcdef0123456789abcdef"

QUESTION = "星笺为什么把文章比作星辰？"

#: 会随时间变化的字段。归一化而不是删掉：删了就没法证明「这个键真的会被填」
VOLATILE = ("atMs", "latencyMs")


def _normalize(payload: dict[str, Any]) -> dict[str, Any]:
    events = []
    for index, event in enumerate(payload["events"]):
        clean = {key: value for key, value in event.items() if key not in VOLATILE}
        # 用序号代替时间戳：形状与真实响应一致，但每次生成的结果逐字节相同
        clean = {"atMs": index, "kind": clean.pop("kind"), **clean}
        events.append(clean)
    return {"traceId": payload["traceId"], "found": True, "events": events}


async def _record_model_call() -> None:
    """再记一条**真实代码路径**上的模型调用事件。

    用 `httpx.MockTransport` 而不是 FakeProvider：模型事件的埋点在
    `OpenAICompatibleProvider` 里（那个类才是真正打上游的地方），
    而 FakeProvider 不走它 —— 用假的就会写出一个「只有检索」的样例，
    契约测试也就守不住模型那一段的键名。
    """

    def handler(request: httpx.Request) -> httpx.Response:
        del request
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": "因为每篇文章都对应夜空中的一个坐标。"}}],
                "usage": {"prompt_tokens": 49, "completion_tokens": 51},
            },
        )

    config = ProviderConfig(
        role="chat",
        provider="openai_compatible",
        base_url="https://example.invalid/v1",
        model="fixture-chat",
        api_key="sk-fixture",
        capabilities=ProviderCapabilities(chat=True),
    )
    provider = OpenAICompatibleProvider(config, transport=httpx.MockTransport(handler))
    await provider.chat([ChatMessage(MessageRole.USER, "星笺为什么把文章比作星辰？")])
    await provider.aclose()


async def main() -> None:
    reset_traces()
    corpus = build_corpus(load_seed_posts())
    fake = FakeProvider()
    pipeline = RetrievalPipeline(
        corpus=corpus,
        config=RetrievalConfig(enable_sparse=True, enable_dense=True, label="fixture"),
        embedder=fake,
    )
    with bind_trace(TRACE_ID):
        await pipeline.retrieve(QUESTION, top_k=3)
        await _record_model_call()
        snapshot = trace_snapshot(TRACE_ID)

    if snapshot is None or not snapshot["events"]:
        # 空样例会让两侧的契约测试都「通过」——那是最坏的结果
        raise SystemExit("没有采集到任何 trace 事件：fixture 会是空的，先查埋点")
    kinds = {event["kind"] for event in snapshot["events"]}
    missing = {"retrieval", "model"} - kinds
    if missing:
        raise SystemExit(f"样例缺了这些链路：{sorted(missing)}（契约样例必须覆盖它们）")

    FIXTURE.write_text(
        json.dumps(_normalize(snapshot), ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"已写入 {FIXTURE.name}：{len(snapshot['events'])} 条事件")
    for event in snapshot["events"]:
        print(f"  - {event['kind']}")


if __name__ == "__main__":
    use_utf8_console()
    asyncio.run(main())
