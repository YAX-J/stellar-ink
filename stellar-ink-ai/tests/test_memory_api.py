"""记忆端点的契约与边界（M9-2）。

三条要守的：

1. **请求体里没有 `userId`** —— 身份只从签名头来；一旦能传，构造参数就能越权；
2. **`/memory/plan` 不假装校验出处**：它拿不到上下文，所以只做计划（这一条是刻意的设计，
   用一个断言把「它没有重新校验」钉住，免得日后有人以为这里也守着出处这道门）；
3. 三个端点都在**内部签名**保护之下（不在 `PUBLIC_PATHS` 里），对外由 ai-service 转发。
"""

from __future__ import annotations

import json

import pytest
from fastapi import FastAPI

from app.main import create_app
from app.schemas.memory import (
    MemoryExtractRequest,
    MemoryPlanRequest,
    MemoryRecallRequest,
)
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    from app.core.internal_auth import InternalRequestVerifier

    return create_app(verifier=InternalRequestVerifier(secret))


async def post(app: FastAPI, secret: str, path: str, payload: dict) -> tuple[int, dict]:
    body = json.dumps(payload)
    headers = {
        **signed_headers("POST", path, secret=secret, body=body, role="AUTHOR", user_id=7),
        "Content-Type": "application/json",
    }
    response = await call(app, "POST", path, headers=headers, content=body)
    return response.status_code, response.json()


def test_memory_requests_carry_no_user_id_field() -> None:
    """**用户身份不能进请求体**：否则「替我查用户 X 的记忆」就是一个越权入口。"""
    for model in (MemoryExtractRequest, MemoryPlanRequest, MemoryRecallRequest):
        fields = set(model.model_fields)
        assert not [name for name in fields if "user" in name.lower()], (
            f"{model.__name__} 不该有 userId/user_id 字段"
        )


async def test_memory_paths_need_internal_signature(app: FastAPI) -> None:
    """三个端点都受内部签名保护：不带签名头直接打应当被拒。"""
    for path in ("/memory/candidates", "/memory/plan", "/memory/recall"):
        response = await call(app, "POST", path, json={})
        assert response.status_code == 401, f"{path} 竟然不需要签名：{response.status_code}"


async def test_recall_endpoint_filters_and_orders(app: FastAPI, secret: str) -> None:
    status, body = await post(
        app,
        secret,
        "/memory/recall",
        {
            "memories": [
                {
                    "memoryId": 1,
                    "type": "preference",
                    "content": "偏好短句",
                    "confidence": 0.9,
                    "status": "active",
                },
                {
                    "memoryId": 2,
                    "type": "preference",
                    "content": "已禁用",
                    "confidence": 0.99,
                    "status": "disabled",
                },
                {
                    "memoryId": 3,
                    "type": "fact",
                    "content": "事实",
                    "confidence": 0.9,
                    "status": "active",
                },
            ],
            "types": ["preference"],
            "limit": 5,
        },
    )

    assert status == 200
    assert body["memoryIds"] == [1], "禁用的不召回；类型过滤掉 fact"


async def test_recall_says_so_when_nothing_is_left(app: FastAPI, secret: str) -> None:
    """「没有可召回的记忆」不是故障 —— 要能说清，而不是回一个空列表让人猜。"""
    status, body = await post(
        app,
        secret,
        "/memory/recall",
        {
            "memories": [
                {
                    "memoryId": 1,
                    "type": "preference",
                    "content": "低可信",
                    "confidence": 0.1,
                    "status": "active",
                }
            ],
            "minConfidence": 0.8,
        },
    )

    assert status == 200
    assert body["memoryIds"] == []
    assert any("没有可召回" in note for note in body["notes"])


async def test_plan_reports_conflicts_without_deciding(app: FastAPI, secret: str) -> None:
    status, body = await post(
        app,
        secret,
        "/memory/plan",
        {
            "existing": [
                {
                    "memoryId": 11,
                    "type": "preference",
                    "content": "作者偏好把文章写长，一次讲透",
                    "confidence": 0.9,
                    "status": "active",
                    "evidence": [{"kind": "quote", "ref": "我习惯一次讲透"}],
                }
            ],
            "candidates": [
                {
                    "type": "preference",
                    "content": "作者偏好把文章写短，一次只讲一件事",
                    "confidence": 0.6,
                    "source": "model_suggested",
                    "evidence": [{"kind": "quote", "ref": "以后一次只讲一件事"}],
                }
            ],
        },
    )

    assert status == 200
    assert body["toAdd"] == []
    assert body["conflicts"][0]["memoryId"] == 11
    assert body["conflicts"][0]["existingContent"].startswith("作者偏好把文章写长")
    assert any("不自动覆盖" in note for note in body["notes"])


async def test_plan_does_not_revalidate_evidence(app: FastAPI, secret: str) -> None:
    """`/memory/plan` **不假装校验出处**：它的输入是已校验过的候选，手里没有上下文。

    这条断言刻意写成「没有上下文的候选照样能进计划」—— 如果哪天有人在这里加了
    「拼个假上下文再校验一次」，这个用例会红，逼他重新想一遍：
    走过场的校验会让读代码的人以为这一层也守着出处这道门。
    """
    status, body = await post(
        app,
        secret,
        "/memory/plan",
        {
            "existing": [],
            "candidates": [
                {
                    "type": "decision",
                    "content": "星笺系列不再写第二季",
                    "confidence": 0.6,
                    "source": "user_stated",
                    "evidence": [{"kind": "quote", "ref": "这句从来没有出现在任何上下文里"}],
                }
            ],
        },
    )

    assert status == 200
    assert [item["content"] for item in body["toAdd"]] == ["星笺系列不再写第二季"]


async def test_extract_rejects_over_long_conversation(app: FastAPI, secret: str) -> None:
    """当前对话只是工作记忆（roadmap M9 第 1 条）：长度超限直接 422，不静默截断。"""
    status, _ = await post(
        app, secret, "/memory/candidates", {"conversation": "长" * 9000, "maxCandidates": 3}
    )

    assert status == 422
