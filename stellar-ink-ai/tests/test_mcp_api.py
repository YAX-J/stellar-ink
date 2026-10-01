"""MCP 的 HTTP 出口：签名、身份透传与错误体形状。

这一层要守的三件事：
1. **没有内部签名一律 401** —— `/mcp` 不在公开白名单里，身份只由 Java 的签名头带进来；
2. **协议错误放在 body 里（HTTP 200）**：用 HTTP 4xx 表达 `-32700` 会让客户端的协议层
   把「格式错误」误当成「服务不可用」，重试策略就完全错了；
3. **身份来自签名头而不是参数**：`_meta.role` 必须等于签名头里的角色。
"""

from __future__ import annotations

import json

import pytest
from fastapi import FastAPI

from app.main import create_app
from tests.fake_providers import install_fake_providers
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers

PATH = "/mcp"


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    from app.core.internal_auth import InternalRequestVerifier

    install_fake_providers()
    return create_app(verifier=InternalRequestVerifier(secret))


async def post_rpc(
    app: FastAPI, secret: str, payload: object, *, role: str = "READER", raw: str | None = None
) -> tuple[int, dict]:
    body = raw if raw is not None else json.dumps(payload, ensure_ascii=False)
    headers = {
        **signed_headers("POST", PATH, secret=secret, body=body, role=role, user_id=7),
        "Content-Type": "application/json",
    }
    response = await call(app, "POST", PATH, headers=headers, content=body)
    return response.status_code, response.json()


def rpc(method: str, params: dict | None = None, *, request_id: int = 1) -> dict:
    payload: dict = {"jsonrpc": "2.0", "method": method, "id": request_id}
    if params is not None:
        payload["params"] = params
    return payload


async def test_mcp_needs_internal_signature(app: FastAPI) -> None:
    """MCP 是内部协议面：没有签名就等于匿名访问只读工具，不行。"""
    response = await call(app, "POST", PATH, json=rpc("tools/list"))

    assert response.status_code == 401


async def test_initialize_over_http(app: FastAPI, secret: str) -> None:
    status, body = await post_rpc(app, secret, rpc("initialize"))

    assert status == 200
    assert body["result"]["protocolVersion"] == "2025-06-18"
    assert body["result"]["serverInfo"]["name"] == "stellar-ink-ai"


async def test_tools_list_over_http_exposes_the_real_search_tool(app: FastAPI, secret: str) -> None:
    _, body = await post_rpc(app, secret, rpc("tools/list"))

    names = [tool["name"] for tool in body["result"]["tools"]]
    assert names == ["search_posts"], "工具集与 Agent 是同一份（read_only_tools）"
    entry = body["result"]["tools"][0]
    assert entry["inputSchema"]["properties"]["question"]["type"] == "string"
    assert entry["annotations"]["readOnlyHint"] is True


async def test_role_comes_from_the_signed_header(app: FastAPI, secret: str) -> None:
    _, body = await post_rpc(
        app,
        secret,
        rpc("tools/call", {"name": "search_posts", "arguments": {"question": "星笺是什么"}}),
        role="ADMIN",
    )

    meta = body["result"]["_meta"]
    assert meta["role"] == "ADMIN"
    assert meta["traceId"] == "trace-42", "traceId 也要透出来，才能顺回调用账"
    assert meta["readOnly"] is True


async def test_extra_arguments_are_rejected_over_http(app: FastAPI, secret: str) -> None:
    """HTTP 这一层同样拒绝 schema 之外的参数（身份字段只能来自签名头）。"""
    status, body = await post_rpc(
        app,
        secret,
        rpc(
            "tools/call",
            {"name": "search_posts", "arguments": {"question": "q", "userId": 1}},
        ),
    )

    assert status == 200, "协议错误走 body，不走 HTTP 状态码"
    assert body["error"]["code"] == -32602
    assert "userId" in body["error"]["message"]


async def test_unknown_method_over_http(app: FastAPI, secret: str) -> None:
    status, body = await post_rpc(app, secret, rpc("resources/list"))

    assert status == 200
    assert body["error"]["code"] == -32601


async def test_broken_json_over_http(app: FastAPI, secret: str) -> None:
    status, body = await post_rpc(app, secret, {}, raw="{ 不是 JSON")

    assert status == 200
    assert body["error"]["code"] == -32700
    assert body["id"] is None


async def test_notification_is_accepted_without_a_response(app: FastAPI, secret: str) -> None:
    """通知没有 id：回 202 空体。回一个 JSON-RPC 响应就是协议违规。"""
    body = json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"})
    headers = {
        **signed_headers("POST", PATH, secret=secret, body=body, role="READER"),
        "Content-Type": "application/json",
    }

    response = await call(app, "POST", PATH, headers=headers, content=body)

    assert response.status_code == 202
    assert response.content in (b"", b"null")
