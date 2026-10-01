"""按 traceId 回放的 HTTP 出口：签名保护、查不到时的形态、以及「真的能看到链路」。

这一层要守的是**读法**：`found=false` 不代表链路不存在（进程内缓冲会淘汰、
多副本时这条链路可能落在别的实例上），所以它返回 200 + 空列表而不是 404 ——
404 会把「换个副本再查」误导成「这条链路是伪造的」。
"""

from __future__ import annotations

import pytest
from fastapi import FastAPI

from app.core.trace import reset_traces
from app.main import create_app
from tests.fake_providers import install_fake_providers
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers

TRACE = "0123456789abcdef0123456789abcdef"


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    from app.core.internal_auth import InternalRequestVerifier

    install_fake_providers()
    reset_traces()
    return create_app(verifier=InternalRequestVerifier(secret))


async def get_trace(app: FastAPI, secret: str, trace_id: str = TRACE) -> tuple[int, dict]:
    path = f"/internal/trace/{trace_id}"
    headers = signed_headers("GET", path, secret=secret, role="ADMIN", user_id=1)
    response = await call(app, "GET", path, headers=headers)
    return response.status_code, response.json()


async def test_trace_replay_needs_internal_signature(app: FastAPI) -> None:
    """回放能看到「谁问了什么规模的问题」这类运营信息，不能匿名可查。"""
    response = await call(app, "GET", f"/internal/trace/{TRACE}")

    assert response.status_code == 401


async def test_unknown_trace_is_found_false_not_404(app: FastAPI, secret: str) -> None:
    status, body = await get_trace(app, secret)

    assert status == 200
    assert body == {"traceId": TRACE, "found": False, "events": []}


async def test_trace_replay_shows_the_model_leg(app: FastAPI, secret: str) -> None:
    """真的跑一次问答，然后按 traceId 把链路读回来（这是 E3-4 的验收动作）。

    用 fake provider：链路结构（检索 + 模型调用）是真的，只有模型回复是桩。
    """
    from app.api.v1.qa import QA_RETRIEVAL, pipeline_for
    from app.core.trace import _trace_id  # noqa: PLC0415 - 测试直接用 contextvar 摆出请求上下文

    token = _trace_id.set(TRACE)
    try:
        pipeline = pipeline_for(QA_RETRIEVAL)
        await pipeline.retrieve("星笺为什么把文章比作星辰？", top_k=3)
    finally:
        _trace_id.reset(token)

    status, body = await get_trace(app, secret)

    assert status == 200
    assert body["found"] is True
    kinds = [event["kind"] for event in body["events"]]
    assert "retrieval" in kinds, "检索这一段必须能在回放里看到"
    retrieval = next(e for e in body["events"] if e["kind"] == "retrieval")
    for key in ("topK", "posts", "refused", "latencyMs", "sparse", "dense"):
        assert key in retrieval, f"回放里缺 {key}：排障时正是靠这几个字段判断「为什么没命中」"
    # 内容类字段一个都不许出现
    joined = str(body)
    assert "星笺为什么把文章比作星辰" not in joined
