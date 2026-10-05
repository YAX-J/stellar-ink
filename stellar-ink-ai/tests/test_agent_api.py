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
from tests.signing import FIXED_NONCE, FIXED_TIMESTAMP_MS, call, load_vector, signed_headers

QUESTION = "一年写十八万字的方法是什么？"

RESULT_FIELDS = {
    "agent",
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


async def post_agent(
    app: FastAPI, secret: str, payload: dict, *, nonce: str = FIXED_NONCE
) -> tuple[int, dict]:
    body = json.dumps(payload, ensure_ascii=False)
    headers = {
        **signed_headers(
            "POST", "/agent/ask", secret=secret, body=body, role="AUTHOR", user_id=1, nonce=nonce
        ),
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
    assert payload["agent"] == "searcher", "缺省司职就是今天的形态，且要回显出来"
    assert payload["doneReason"] in {"stop", "length", "cancelled", "error", "refused"}
    assert isinstance(payload["steps"], list)
    assert payload["toolCalls"] >= 0
    for step in payload["steps"]:
        assert set(step) == STEP_FIELDS


async def test_unknown_agent_is_rejected_with_the_options(app: FastAPI, secret: str) -> None:
    """未知司职 → 422，且消息里列出可选值（**不猜、不回退默认**）。"""
    status, payload = await post_agent(app, secret, {"question": QUESTION, "agent": "search"})

    assert status == 422
    message = payload["message"]
    assert "search" in message
    for option in ("answerer", "searcher", "verifier"):
        assert option in message, f"422 消息里要列出可选司职：{option}"


async def test_named_searcher_still_works(app: FastAPI, secret: str) -> None:
    """显式点名缺省司职必须与不点名完全一致（骨架化不该改变默认行为）。"""
    implicit_status, implicit = await post_agent(app, secret, {"question": QUESTION})
    explicit_status, explicit = await post_agent(
        app,
        secret,
        {"question": QUESTION, "agent": "searcher"},
        # nonce 必须每次不同：防重放是按 nonce 判的，复用会让第二次请求 401
        nonce="a1b2c3d4e5f60718293a4b5c6d7e8f90",
    )

    assert implicit_status == explicit_status == 200
    assert explicit["agent"] == "searcher"
    assert set(explicit) == set(implicit)
    assert explicit["doneReason"] == implicit["doneReason"]
    assert explicit["answer"] == implicit["answer"]
    assert explicit["toolCalls"] == implicit["toolCalls"]


async def test_answerer_runs_the_generation_path(app: FastAPI, secret: str) -> None:
    """无工具司职：不跑循环，但也**不假装**跑过 —— `steps` 为空、`toolCalls=0`。

    离线桩（Fake 回显）下它会如实收尾（回显内容解析不出决策协议，答案为空），
    关键断言是形状：没有工具调用记录，司职名正确回显。
    """
    status, payload = await post_agent(app, secret, {"question": QUESTION, "agent": "answerer"})

    assert status == 200
    assert payload["agent"] == "answerer"
    assert payload["steps"] == [], "纯生成没有多步记录，不能编一条出来"
    assert payload["toolCalls"] == 0
    assert payload["doneReason"] in {"stop", "length", "refused", "error"}


def test_answerer_is_built_with_the_profile_settings() -> None:
    """装配层必须把司职的 `settings` 交给生成器。

    配了 `temperature` / `maxTokens` 却没人读，表现出来是「同一个司职时而稳时而不稳」——
    那是最费劲的一类问题（看起来像模型问题，实际是接线问题）。
    """
    from app.agents import answerer as answerer_module
    from app.api.v1.agent import build_answerer
    from app.rag.agent import AgentSettings

    # 装配会去取 chat 模型：先显式装桩（与其它用例同一条纪律：不依赖机器上的 .env）
    install_fake_providers()
    # `AgentSettings` 是 frozen dataclass，比较是**按值**的
    assert AgentSettings() != answerer_module.PROFILE.settings, "断言要能区分默认值与司职值"

    runner = build_answerer(answerer_module.PROFILE, user_id=7)

    assert runner.settings == answerer_module.PROFILE.settings


async def test_profile_list_is_signed_and_lists_the_profiles(app: FastAPI, secret: str) -> None:
    """清单本身也要签名（它属于内网端点），但内容对所有登录用户相同。"""
    headers = signed_headers("GET", "/agent/profiles", secret=secret, role="READER", user_id=5)
    response = await call(app, "GET", "/agent/profiles", headers=headers)

    assert response.status_code == 200
    payload = response.json()
    assert payload["defaultAgent"] == "searcher"
    names = [item["name"] for item in payload["agents"]]
    assert names == ["answerer", "searcher", "verifier"]
    assert all(item["maxSteps"] >= 1 for item in payload["agents"])
    assert "systemPrompt" not in json.dumps(payload, ensure_ascii=False), "清单不出口提示词"


async def test_profile_list_requires_signature(app: FastAPI) -> None:
    response = await call(app, "GET", "/agent/profiles")

    assert response.status_code == 401


async def test_verifier_is_visible_but_not_an_answerer(app: FastAPI, secret: str) -> None:
    """核验员在清单里可见，但 **`/agent/ask` 不产出它的答案**：一句可读的 400 指路。

    A2 把它接成了**确定性核验**（独立端点 `/agent/verify`）。这条断言因此换了语义 ——
    从「尚未接线」变成「它不在这条路上」，而**意图不变**：
    绝不能在「用户点了核验员」时假装生成了一段答案。
    """
    status, payload = await post_agent(app, secret, {"question": QUESTION, "agent": "verifier"})

    assert status == 400
    assert "verifier" in payload["message"]
    assert "/agent/verify" in payload["message"], "要说清该走哪条路径，而不是只说「不行」"


async def test_implicit_budget_comes_from_the_profile(app: FastAPI, secret: str) -> None:
    """不传预算时用司职的默认值（4 步）—— 与今天完全一致。

    离线桩下每一步都是「格式不符」，所以步数就等于预算：跑满 4 步才收尾。
    """
    _, payload = await post_agent(app, secret, {"question": QUESTION})

    assert len(payload["steps"]) == 4, "缺省预算仍应是 4 步"


async def test_requested_budget_can_only_be_tightened(app: FastAPI, secret: str) -> None:
    """传比司职默认更大的值不会放宽预算（`min(请求值, 司职上限)`）。"""
    _, tight = await post_agent(app, secret, {"question": QUESTION, "maxSteps": 2})
    _, loose = await post_agent(
        app,
        secret,
        {"question": QUESTION, "maxSteps": 4},
        nonce="ffeeddccbbaa99887766554433221100",
    )

    assert len(tight["steps"]) == 2
    assert len(loose["steps"]) == 4, "传 4（= 司职默认）不会被放宽"


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


async def test_rejection_messages_do_not_leak_internals(app: FastAPI, secret: str) -> None:
    """未知司职的 422 也要经得起这条检查：它是一条**新的**对外错误消息。"""
    _, unknown = await post_agent(app, secret, {"question": QUESTION, "agent": "search_posts"})
    _, skeleton = await post_agent(
        app,
        secret,
        {"question": QUESTION, "agent": "verifier"},
        nonce="deadbeefdeadbeefdeadbeefdeadbeef",
    )

    for payload in (unknown, skeleton):
        body = json.dumps(payload, ensure_ascii=False)
        for forbidden in ("8200", "127.0.0.1", "AI_INTERNAL_SECRET", "sk-", "Traceback"):
            assert forbidden not in body, f"错误消息泄露了内部信息：{forbidden}"


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
