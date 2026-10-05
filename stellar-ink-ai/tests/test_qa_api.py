"""问答接口测试：契约形状、鉴权与「不泄露内部信息」。

编排逻辑由 `test_qa.py` 覆盖；这里只关心**接口层**：签名保护、请求校验、响应字段
与 QaAnswer 契约一致、以及别把内网地址之类的东西带出去。
"""

from __future__ import annotations

import json

import pytest
from fastapi import FastAPI

from app.api.v1.qa import QA_RETRIEVAL
from app.core.internal_auth import InternalRequestVerifier
from app.main import create_app
from tests.fake_providers import install_fake_providers, install_no_providers
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    # 显式注入「用桩」这件事：测试不再是「因为代码里有 Fake 默认值所以能跑」，
    # 而是「显式要求用桩」——前者让「忘了配真实模型」也能悄悄通过测试
    install_fake_providers()
    return create_app(verifier=InternalRequestVerifier(secret))


async def post(app: FastAPI, secret: str, payload: dict) -> tuple[int, dict]:
    body = json.dumps(payload, ensure_ascii=False)
    headers = {
        **signed_headers("POST", "/qa", secret=secret, body=body, role="READER", user_id=5),
        "Content-Type": "application/json",
    }
    response = await call(app, "POST", "/qa", headers=headers, content=body)
    return response.status_code, response.json()


async def test_qa_requires_signature(app: FastAPI) -> None:
    """问答会消耗算力甚至模型费用，绝不能匿名触发。"""
    response = await call(app, "POST", "/qa", json={"question": "作者为什么坚持写博客？"})

    assert response.status_code == 401


async def test_qa_returns_the_answer_contract(app: FastAPI, secret: str) -> None:
    status, payload = await post(app, secret, {"question": "作者为什么坚持写博客？", "topK": 5})

    assert status == 200
    assert set(payload) == {"answer", "citations", "doneReason", "usage", "evidenceSufficient"}
    assert payload["answer"], "拒答也有文案，不能是空字符串"
    assert payload["doneReason"] in {"stop", "refused", "length", "cancelled", "error"}
    assert isinstance(payload["evidenceSufficient"], bool)
    assert payload["usage"]["model"] == "fake", "离线自测：模型标识要如实告诉前端"
    for citation in payload["citations"]:
        assert set(citation) == {"kind", "postId", "title", "chunkIndex", "snippet", "score"}
        assert citation["kind"] in {"post", "note"}, "引用要能区分文章与笔记，前端据此跳页面"
        assert citation["snippet"], "引用必须带原文片段，否则无法定位"


async def test_qa_uses_the_shared_corpus(app: FastAPI, secret: str) -> None:
    """问种子内容里确实写过的东西：应当给出引用（否则说明语料没接上）。"""
    status, payload = await post(
        app, secret, {"question": "一年写十八万字的方法是什么？", "topK": 5}
    )

    assert status == 200
    assert payload["evidenceSufficient"] is True
    assert payload["citations"], "语料里有答案却没有引用，说明检索没接上"
    assert all(isinstance(item["postId"], int) for item in payload["citations"])


async def test_blank_question_is_rejected_by_the_contract(app: FastAPI, secret: str) -> None:
    status, payload = await post(app, secret, {"question": "   "})

    assert status == 422, "契约层就该拦下空白问题，不该走到检索"
    assert "detail" in payload


async def test_unknown_field_is_rejected(app: FastAPI, secret: str) -> None:
    """契约 extra=forbid：多写字段说明调用方与契约已经不一致。"""
    status, payload = await post(app, secret, {"question": "星笺是什么？", "temperature": 0.9})

    assert status == 422
    assert "detail" in payload


async def test_response_does_not_leak_internals(app: FastAPI, secret: str) -> None:
    _, payload = await post(app, secret, {"question": "作者为什么坚持写博客？"})

    body = json.dumps(payload, ensure_ascii=False)
    for forbidden in ("8200", "127.0.0.1", "AI_INTERNAL_SECRET", "apiKey", "sk-"):
        assert forbidden not in body, f"响应泄露了内部信息：{forbidden}"


def test_offline_config_does_not_silently_disable_the_dense_path() -> None:
    """离线自测不许设相似度下限：Fake 向量余弦在 0.03 量级，设 0.2 会让向量通路静默失效。

    这条断言看着像在测常量，实际是在拦一类**不会报错的退化**：混合检索悄悄变成纯 BM25，
    而指标、日志、响应一切正常。真嵌入模型接上后要为它标定一个值（见 fastapi `/qa` 的注释）。
    """
    assert QA_RETRIEVAL.enable_dense is True
    assert QA_RETRIEVAL.min_dense_score == 0.0, "接入真实嵌入模型前，别给 Fake 向量设下限"


async def test_missing_model_config_is_a_readable_400(app: FastAPI, secret: str) -> None:
    """面板没配模型时：**说清去配哪个角色**，而不是退回 Fake 或报 500。

    这是「代码里没有默认模型」这条红线的守门测试。退回 Fake 的后果不是「功能差一点」，
    而是「没配好」表现成「回答质量差」—— 那是最难查的一类问题，日志里一切正常。
    """
    install_no_providers()

    status, payload = await post(app, secret, {"question": "作者为什么坚持写博客？"})

    assert status == 400, "配置缺失是请求方（运维/站长）能修的问题，不该是 500"
    assert payload["message"].startswith("角色")
    assert "embedding" in payload["message"], "要说清缺的是哪个角色"
    assert "模型配置" in payload["message"], "只说「没配」等于只说了一半，要给出下一步"
    assert "来源" in payload["message"], "空配置要说明配置是从哪读的（真没配 / 读不到）"
