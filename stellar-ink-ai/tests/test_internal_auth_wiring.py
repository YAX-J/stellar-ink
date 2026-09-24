"""内部验签接线：公开路径放行、受保护路径 fail-closed、身份可被业务读取。

这里验证的是「接线」而不是算法（算法在 `test_internal_auth_verify.py`）：
- 探活与文档不需要签名（运维要能在未登录时判断服务是否活着）；
- 其余路径没有合法签名一律 401，**包括不存在的路径**（默认拒绝，不靠记得加保护）；
- 未配置 `AI_INTERNAL_SECRET` 时同样拒绝，且提示能指导运维；
- 验签通过后，业务依赖能读到 userId/role/traceId。

验签器通过 `create_app(verifier=...)` 显式注入，而不是改环境变量：
应用工厂支持注入，测试就不必依赖进程级状态。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from app.core.config import get_settings
from app.core.internal_auth import (
    HEADER_NONCE,
    HEADER_ROLE,
    HEADER_SIGNATURE,
    HEADER_TIMESTAMP,
    HEADER_TRACE_ID,
    HEADER_USER_ID,
    InternalRequestVerifier,
    body_sha256,
    canonical_request,
    sign,
)
from app.core.internal_auth_middleware import IDENTITY_STATE_KEY, PUBLIC_PATHS, is_public_path
from app.main import create_app

FIXTURES = Path(__file__).parent / "fixtures"

SELF_CHECK_PATH = "/internal/whoami"

#: 固定时间戳与 nonce，便于直接复用向量里的时间窗
FIXED_TIMESTAMP_MS = 1790256000000
FIXED_NONCE = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"


def load_vector() -> dict[str, Any]:
    with (FIXTURES / "signature_vector.json").open(encoding="utf-8") as handle:
        data: dict[str, Any] = json.load(handle)
    return data


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app_with_secret(secret: str) -> FastAPI:
    return create_app(verifier=InternalRequestVerifier(secret))


@pytest.fixture()
def app_without_secret(monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    """显式表达「环境里没有密钥」：删掉变量并不传 verifier。"""
    monkeypatch.delenv("AI_INTERNAL_SECRET", raising=False)
    get_settings.cache_clear()
    app = create_app()
    get_settings.cache_clear()
    return app


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


def test_public_paths_are_exact_matches() -> None:
    assert is_public_path("/health")
    assert is_public_path("/docs")
    assert is_public_path("/openapi.json")
    # 前缀相似的不算公开：默认拒绝，不能靠 startsWith 放行
    assert not is_public_path("/health/x")
    assert not is_public_path("/internal/whoami")
    assert PUBLIC_PATHS == frozenset({"/health", "/docs", "/openapi.json", "/redoc"})


async def test_health_needs_no_signature(app_with_secret: FastAPI) -> None:
    response = await call(app_with_secret, "GET", "/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"


async def test_protected_path_without_signature_is_unauthorized(
    app_with_secret: FastAPI, monkeypatch: pytest.MonkeyPatch
) -> None:
    # 固定时钟，使向量里的时间戳落在窗口内 —— 这样 401 只能是因为缺签名
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    response = await call(app_with_secret, "GET", SELF_CHECK_PATH)

    assert response.status_code == 401
    body = response.json()
    assert body["code"] == "AI_UNAUTHORIZED"
    assert "校验失败" in body["message"]


async def test_protected_path_with_tampered_identity_is_unauthorized(
    app_with_secret: FastAPI, secret: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """签名合法但身份头被改：必须 401（身份在签名里）。"""
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    headers = signed_headers("GET", SELF_CHECK_PATH, secret=secret)
    headers[HEADER_USER_ID] = "1"

    response = await call(app_with_secret, "GET", SELF_CHECK_PATH, headers=headers)

    assert response.status_code == 401


async def test_unknown_path_is_also_rejected_without_signature(
    app_with_secret: FastAPI, monkeypatch: pytest.MonkeyPatch
) -> None:
    """默认拒绝：连 404 的路径也先要过验签，避免出现「未保护的空隙」。"""
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    response = await call(app_with_secret, "GET", "/not-a-real-path")

    assert response.status_code == 401


async def test_signed_request_reaches_business_and_carries_identity(
    app_with_secret: FastAPI, secret: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    headers = signed_headers("GET", SELF_CHECK_PATH, secret=secret, user_id=7, role="ADMIN")

    response = await call(app_with_secret, "GET", SELF_CHECK_PATH, headers=headers)

    assert response.status_code == 200
    payload = response.json()
    assert payload["userId"] == 7
    assert payload["role"] == "ADMIN"
    assert payload["traceId"] == "trace-42"


async def test_body_is_covered_by_signature(
    app_with_secret: FastAPI, secret: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """body 参与签名：签名时用的是 A，发出去的是 B —— 必须拒。"""
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    body = json.dumps({"question": "星笺为什么把文章比作星辰？"}, ensure_ascii=False)
    headers = signed_headers("POST", SELF_CHECK_PATH, secret=secret, body=body)

    tampered = await call(
        app_with_secret, "POST", SELF_CHECK_PATH, headers=headers, content=body + " 改"
    )

    assert tampered.status_code == 401


async def test_missing_secret_fails_closed(app_without_secret: FastAPI) -> None:
    """未配置密钥时受保护路径必须拒绝，且提示要能指导运维。"""
    response = await call(app_without_secret, "GET", SELF_CHECK_PATH)

    assert response.status_code == 401
    assert "AI_INTERNAL_SECRET" in response.json()["message"]

    # 探活仍然可用：配置问题不该让服务看起来「死了」
    assert (await call(app_without_secret, "GET", "/health")).status_code == 200


def test_identity_state_key_is_stable() -> None:
    """业务依赖按这个键读身份；改名会让所有调用点静默拿不到身份。"""
    assert IDENTITY_STATE_KEY == "internal_identity"
