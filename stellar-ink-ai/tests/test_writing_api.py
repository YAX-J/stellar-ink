"""Copilot 接口测试：契约形状、鉴权与「离线桩必须能出候选」。

最后一条是关键：离线桩如果不按提示词要求的格式回答，所有润色请求都会以 502 结束，
而链路其实完全正常 —— 那种「功能看起来是坏的」最容易被误判成实现问题。
"""

from __future__ import annotations

import json

import pytest
from fastapi import FastAPI

from app.api.v1.writing import build_copilot
from app.core.internal_auth import InternalRequestVerifier
from app.main import create_app
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    build_copilot.cache_clear()
    return create_app(verifier=InternalRequestVerifier(secret))


async def post(app: FastAPI, secret: str, payload: dict) -> tuple[int, dict]:
    body = json.dumps(payload, ensure_ascii=False)
    headers = {
        **signed_headers(
            "POST", "/writing/suggest", secret=secret, body=body, role="AUTHOR", user_id=3
        ),
        "Content-Type": "application/json",
    }
    response = await call(app, "POST", "/writing/suggest", headers=headers, content=body)
    return response.status_code, response.json()


DRAFT = "今晚星星很多。我坐在窗边，屋里很安静。我写了很久的字。"


async def test_suggest_requires_signature(app: FastAPI) -> None:
    """写作建议消耗模型额度，绝不能匿名触发。"""
    response = await call(app, "POST", "/writing/suggest", json={"task": "polish", "draft": DRAFT})

    assert response.status_code == 401


async def test_offline_stub_returns_parseable_candidates(app: FastAPI, secret: str) -> None:
    status, payload = await post(
        app, secret, {"task": "polish", "draft": DRAFT, "candidateCount": 2}
    )

    assert status == 200, "离线桩必须能产出候选，否则前端看起来像坏了"
    assert payload["task"] == "polish"
    assert payload["candidates"], "至少要有一个候选"
    for candidate in payload["candidates"]:
        assert set(candidate) == {"text", "rationale"}
        assert candidate["text"].strip()
        assert "离线自测" in (candidate["rationale"] or ""), "桩产出的候选要自报家门"
    assert payload["usage"]["model"] == "fake-copilot", "模型标识要如实告诉前端"


async def test_candidates_are_slices_of_the_draft(app: FastAPI, secret: str) -> None:
    """桩不做改写：候选的每一句都应当能在草稿里原样找到（这样差异预览才有意义）。"""
    _, payload = await post(app, secret, {"task": "continue", "draft": DRAFT})

    assert payload["candidates"]
    for candidate in payload["candidates"]:
        for sentence in (piece for piece in candidate["text"].split("。") if piece):
            assert sentence in DRAFT, candidate


async def test_draft_is_required_for_content_tasks(app: FastAPI, secret: str) -> None:
    status, payload = await post(app, secret, {"task": "polish", "draft": "   "})

    assert status == 422, "契约层就该拦下空草稿，不该走到模型"
    assert "detail" in payload


async def test_title_task_accepts_short_draft(app: FastAPI, secret: str) -> None:
    status, payload = await post(app, secret, {"task": "title", "draft": "夜里的字"})

    assert status == 200
    assert payload["candidates"][0]["text"]


async def test_unknown_field_is_rejected(app: FastAPI, secret: str) -> None:
    status, _ = await post(app, secret, {"task": "polish", "draft": DRAFT, "autoApply": True})

    assert status == 422, "契约 extra=forbid：不允许出现「自动应用」这类字段"


async def test_response_does_not_echo_the_whole_draft(app: FastAPI, secret: str) -> None:
    """草稿是作者的私有内容：响应里只该有候选片段，不该把整段草稿回显一遍。"""
    long_draft = DRAFT * 60
    _, payload = await post(app, secret, {"task": "polish", "draft": long_draft})

    body = json.dumps(payload, ensure_ascii=False)
    assert len(body) < len(long_draft), "响应不该比草稿还长（那说明整段回显了）"
    for forbidden in ("8200", "127.0.0.1", "AI_INTERNAL_SECRET"):
        assert forbidden not in body
