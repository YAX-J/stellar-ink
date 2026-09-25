"""测试用的内部签名助手：所有需要「像 Java 那样签名」的测试都从这里取。

放在单独模块而不是某个测试文件里，是为了让多个测试文件共用同一份实现 ——
签名串一旦写错，测试会以「全都 401」的形式失败，而真正的问题在助手而不是被测代码，
所以这份助手本身也有一条测试（`test_internal_auth_wiring.py` 的向量断言）盯着。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx
from fastapi import FastAPI

from app.core.internal_auth import (
    HEADER_NONCE,
    HEADER_ROLE,
    HEADER_SIGNATURE,
    HEADER_TIMESTAMP,
    HEADER_TRACE_ID,
    HEADER_USER_ID,
    body_sha256,
    canonical_request,
    sign,
)

FIXTURES = Path(__file__).parent / "fixtures"

#: 与 `signature_vector.json` 对齐的固定时间戳（测试会把时钟也固定到它）
FIXED_TIMESTAMP_MS = 1790256000000
FIXED_NONCE = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"


def load_vector() -> dict[str, Any]:
    with (FIXTURES / "signature_vector.json").open(encoding="utf-8") as handle:
        data: dict[str, Any] = json.load(handle)
    return data


def signed_headers(
    method: str,
    path: str,
    *,
    secret: str,
    body: str = "",
    user_id: int = 42,
    role: str = "AUTHOR",
    timestamp_ms: int = FIXED_TIMESTAMP_MS,
    nonce: str = FIXED_NONCE,
) -> dict[str, str]:
    canonical = canonical_request(
        method, path, timestamp_ms, nonce, body_sha256(body), user_id, role
    )
    return {
        HEADER_SIGNATURE: sign(secret, canonical),
        HEADER_TIMESTAMP: str(timestamp_ms),
        HEADER_NONCE: nonce,
        HEADER_USER_ID: str(user_id),
        HEADER_ROLE: role,
        HEADER_TRACE_ID: "trace-42",
    }


async def call(app: FastAPI, method: str, path: str, **kwargs: Any) -> httpx.Response:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://ai.internal") as client:
        return await client.request(method, path, **kwargs)


async def call_stream(
    app: FastAPI, method: str, path: str, **kwargs: Any
) -> tuple[int, str, dict[str, str]]:
    """读完整的流式响应：返回 (状态码, 正文, 响应头)。

    **必须显式读完**再关客户端：`ASGITransport` 下连接一关就等于客户端断开，
    服务端的生成器会收到取消 —— 那样测到的就不是「正常走完的流」了。
    """
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://ai.internal") as client:
        async with client.stream(method, path, **kwargs) as response:
            body = "".join([chunk async for chunk in response.aiter_text()])
            return response.status_code, body, dict(response.headers)
