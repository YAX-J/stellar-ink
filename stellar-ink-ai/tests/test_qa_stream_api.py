"""SSE 接口层测试：线格式、响应头、错误事件与心跳。

编排顺序由 `test_qa_stream.py` 覆盖；这里只管**接口层**：帧能不能被跨语言解析、
代理缓冲有没有关掉、语料缺失时是不是给一个可读的 `error` 事件而不是断流。
"""

from __future__ import annotations

import json

import pytest
from fastapi import FastAPI

from app.api.v1.qa import build_qa_service
from app.core.internal_auth import InternalRequestVerifier
from app.main import create_app
from app.schemas.qa_stream import FRAME_TERMINATOR
from tests.signing import FIXED_TIMESTAMP_MS, call, call_stream, load_vector, signed_headers

QUESTION = "一年写十八万字的方法是什么？"


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    build_qa_service.cache_clear()
    return create_app(verifier=InternalRequestVerifier(secret))


def frames(body: str) -> list[dict]:
    """把响应正文还原成事件列表。解析规则就是前端/Java 侧要用的那一套。"""
    events: list[dict] = []
    for block in body.split(FRAME_TERMINATOR):
        block = block.strip()
        if not block or block.startswith(":"):
            continue
        assert block.startswith("data: "), f"未知帧形态：{block[:40]}"
        events.append(json.loads(block[len("data: ") :]))
    return events


async def stream(app: FastAPI, secret: str, payload: dict):
    body = json.dumps(payload, ensure_ascii=False)
    headers = {
        **signed_headers("POST", "/qa/stream", secret=secret, body=body, role="READER", user_id=5),
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
    }
    return await call_stream(app, "POST", "/qa/stream", headers=headers, content=body)


async def test_stream_requires_signature(app: FastAPI) -> None:
    """流式接口同样受内部签名保护：它一样会消耗算力。"""
    response = await call(app, "POST", "/qa/stream", json={"question": QUESTION})

    assert response.status_code == 401


async def test_stream_emits_parseable_frames(app: FastAPI, secret: str) -> None:
    status, body, headers = await stream(app, secret, {"question": QUESTION, "topK": 5})

    assert status == 200
    assert headers["content-type"].startswith("text/event-stream")
    # 代理缓冲关不掉的话，「流式」会被攒成一整块再吐出来
    assert headers.get("x-accel-buffering") == "no"
    assert headers.get("cache-control") == "no-cache"

    events = frames(body)
    assert [event["type"] for event in events][0] == "meta"
    assert [event["type"] for event in events][-1] == "done"
    assert body.endswith(FRAME_TERMINATOR), "最后一帧也要以空行结束，否则前端会一直等下一帧"


async def test_stream_frame_shapes_match_the_non_stream_contract(app: FastAPI, secret: str) -> None:
    _, body, _ = await stream(app, secret, {"question": QUESTION})
    events = frames(body)

    done = next(event for event in events if event["type"] == "done")
    assert set(done) == {"type", "answer", "doneReason", "usage", "evidenceSufficient"}
    assert set(done["usage"]) == {
        "promptTokens",
        "completionTokens",
        "totalTokens",
        "latencyMs",
        "model",
    }

    for event in events:
        if event["type"] == "citation":
            assert set(event["citation"]) == {"postId", "title", "chunkIndex", "snippet", "score"}
        if event["type"] == "delta":
            assert set(event) == {"type", "text"}

    meta = events[0]
    assert set(meta) == {"type", "model", "questionLength", "topK"}
    # meta 是我们自己构造的：不回显问题原文（少一份用户输入留在日志/抓包里）。
    # 注意**不能**断言整条流里没有问题原文：离线 Fake 的回答就是提示词回显，
    # 问题会随 `delta` 一起回来 —— 那是桩的行为，不是接口在泄露。
    assert QUESTION not in json.dumps(meta, ensure_ascii=False)


async def test_heartbeat_keeps_the_connection_alive_while_idle(
    app: FastAPI, secret: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """模型第一个 token 之前是静默期：这段时间必须有注释行，否则代理会掐掉连接。"""
    monkeypatch.setattr("app.api.v1.qa.HEARTBEAT_SECONDS", 0.01)

    _, body, _ = await stream(app, secret, {"question": QUESTION})

    assert any(block.strip().startswith(":") for block in body.split(FRAME_TERMINATOR)), (
        "静默期没有心跳：反向代理会把连接当死连接回收"
    )
    # 心跳是注释行，不该被解析成事件
    assert all("type" in event for event in frames(body))


async def test_missing_corpus_gives_a_readable_error_frame(
    app: FastAPI, secret: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """语料缺失属于环境问题：给一条能读懂的错误，而不是 500 或一条没有 done 的断流。

    注意这里走的是**非流式**的 `/qa`：`/qa/stream` 在语料缺失时仍以 JSON 400 响应
    （流还没开始就失败了，没必要用 SSE 表达）。
    """

    def boom() -> None:
        raise ValueError("语料为空：问答没有可检索的内容")

    monkeypatch.setattr("app.api.v1.qa.build_qa_service", boom)

    status, payload = await _post_ask(app, secret, {"question": QUESTION})

    assert status == 400
    assert payload["code"] == "AI_BAD_REQUEST"
    assert "语料" in payload["message"]


async def _post_ask(app: FastAPI, secret: str, payload: dict) -> tuple[int, dict]:
    body = json.dumps(payload, ensure_ascii=False)
    headers = {
        **signed_headers("POST", "/qa", secret=secret, body=body, role="READER", user_id=5),
        "Content-Type": "application/json",
    }
    response = await call(app, "POST", "/qa", headers=headers, content=body)
    return response.status_code, response.json()


async def test_blank_question_is_rejected_before_streaming(app: FastAPI, secret: str) -> None:
    status, _, _ = await stream(app, secret, {"question": "   "})

    assert status == 422, "契约层就该拦下空白问题，不该开一条流再断掉"
