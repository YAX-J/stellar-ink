"""Agent 接口测试：签名保护、契约形状、预算如实透出。

编排逻辑由 `tests/test_agent.py` 覆盖（用脚本化模型精确断言循环）；
这里只关心**接口层**：受内部签名保护、字段与契约一致、
离线装配下不会假装「答得很好」（`doneReason` 要如实反映预算触顶）。

「离线装配」是**显式配出来的**（`install_fake_providers`）：Agent 会反复调用模型，
代码里留默认值等于让「忘了配模型」也能跑起来 —— 那正是要拦住的事。
"""

from __future__ import annotations

import json

import pytest
from fastapi import FastAPI

from app.api.v1.agent import to_result
from app.core.internal_auth import InternalRequestVerifier
from app.main import create_app
from app.rag.agent import AgentRun, AgentStep, StopReason
from app.schemas.common import Citation
from tests.fake_providers import install_fake_providers, install_no_providers
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers

QUESTION = "一年写十八万字的方法是什么？"

RESULT_FIELDS = {
    "answer",
    "citations",
    "doneReason",
    "steps",
    "toolCalls",
    "interruptedBy",
    "usageModel",
    "latencyMs",
}
STEP_FIELDS = {"index", "thought", "tool", "label", "error"}


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    install_fake_providers()
    return create_app(verifier=InternalRequestVerifier(secret))


async def post_agent(app: FastAPI, secret: str, payload: dict) -> tuple[int, dict]:
    body = json.dumps(payload, ensure_ascii=False)
    headers = {
        **signed_headers("POST", "/agent/ask", secret=secret, body=body, role="AUTHOR", user_id=1),
        "Content-Type": "application/json",
    }
    response = await call(app, "POST", "/agent/ask", headers=headers, content=body)
    return response.status_code, response.json()


async def test_agent_requires_signature(app: FastAPI) -> None:
    """Agent 会反复调用模型与检索，绝不能匿名触发。"""
    response = await call(app, "POST", "/agent/ask", json={"question": QUESTION})

    assert response.status_code == 401


async def test_agent_returns_the_contract(app: FastAPI, secret: str) -> None:
    status, payload = await post_agent(app, secret, {"question": QUESTION})

    assert status == 200
    assert set(payload) == RESULT_FIELDS
    assert payload["doneReason"] in {"stop", "length", "cancelled", "error", "refused"}
    assert isinstance(payload["steps"], list)
    assert payload["toolCalls"] >= 0
    for step in payload["steps"]:
        assert set(step) == STEP_FIELDS


async def test_offline_agent_does_not_pretend_to_have_answered(app: FastAPI, secret: str) -> None:
    """离线模型（Fake 回显）解析不出决策 JSON：必须如实标成预算触顶，而不是编一个答案。

    这条断言的价值在于**它守的是诚实性**：假装配下最容易出的事是「随便返回点什么，
    看起来像成功了」——那样前端与评测都会把噪声当成结果。
    """
    _, payload = await post_agent(app, secret, {"question": QUESTION, "maxSteps": 2})

    assert payload["doneReason"] in {"length", "error"}
    assert payload["answer"] == ""
    assert payload["steps"], "跑了就要留下逐步记录，哪怕结果是「没收敛」"
    assert any(step["error"] for step in payload["steps"]), "格式不符要留下原因"
    assert payload["toolCalls"] == 0, "模型没给出可执行工具名时不该乱调工具"


async def test_max_steps_is_bounded_by_the_contract(app: FastAPI, secret: str) -> None:
    status, payload = await post_agent(app, secret, {"question": QUESTION, "maxSteps": 99})

    assert status == 422, "超出上限要在契约层拦下（预算是硬上限，不能由客户端放宽）"
    assert "detail" in payload


async def test_blank_question_is_rejected(app: FastAPI, secret: str) -> None:
    status, payload = await post_agent(app, secret, {"question": "   "})

    assert status == 422
    assert "detail" in payload


async def test_agent_does_not_leak_internals(app: FastAPI, secret: str) -> None:
    _, payload = await post_agent(app, secret, {"question": QUESTION})

    body = json.dumps(payload, ensure_ascii=False)
    for forbidden in ("8200", "127.0.0.1", "AI_INTERNAL_SECRET", "apiKey", "sk-"):
        assert forbidden not in body, f"响应泄露了内部信息：{forbidden}"


async def test_missing_chat_model_is_a_readable_400(app: FastAPI, secret: str) -> None:
    """面板没配 chat 角色：报 400 并指路，而不是退回桩。

    Agent 是**最贵**的一条链路（每一步都要问一次模型、还要调检索），
    让它在一个「没配好」的环境里跑起来，烧的是钱、拿到的是噪声。
    """
    install_no_providers()

    status, payload = await post_agent(app, secret, {"question": QUESTION})

    assert status == 400
    assert "chat" in payload["message"]
    assert "模型配置" in payload["message"]


def test_to_result_maps_every_field_without_judgement() -> None:
    """翻译层只搬字段：预算/中断的判断都在编排层，接口层不能自己再判断一遍。"""
    run = AgentRun(
        answer="答案",
        citations=[Citation(post_id=1, title="标题", chunk_index=2, snippet="片段", score=1.5)],
        stop_reason=StopReason.LENGTH,
        steps=[AgentStep(index=0, thought="想", tool="search_posts", label="检索到 1 段")],
        tool_calls=1,
        interrupted_by="budget",
        usage_model="fake",
        latency_ms=12,
    )

    result = to_result(run)

    assert result.answer == "答案"
    assert result.done_reason == "length", "预算触顶要如实映射（不是 stop）"
    assert result.interrupted_by == "budget"
    assert result.tool_calls == 1
    assert result.steps[0].tool == "search_posts"
    assert result.citations[0].chunk_index == 2
