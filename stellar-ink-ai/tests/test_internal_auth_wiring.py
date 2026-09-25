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

import pytest
from fastapi import FastAPI

from app.core.config import get_settings
from app.core.internal_auth import HEADER_USER_ID, InternalRequestVerifier
from app.core.internal_auth_middleware import IDENTITY_STATE_KEY, PUBLIC_PATHS, is_public_path
from app.main import create_app
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers

SELF_CHECK_PATH = "/internal/whoami"


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


async def test_replay_hands_the_real_receive_back_after_the_body() -> None:
    """读完 body 之后必须把真实 receive 交回下游，**不能伪造断开**。

    这条是 SSE 500 的根因回归测试：`InternalAuthMiddleware` 原先第二次调用就返回
    `http.disconnect`，而非流式接口完全正常 —— 但 `BaseHTTPMiddleware`（traceId）在响应
    进入流式发送后会用 receive 等断开信号，拿到假的「已断开」就把整个响应任务组取消，
    于是 `http.response.start` 还没发出去，Starlette 报 "No response returned"。
    换句话说：伪造断开等于自己掐断自己的流。
    """
    from app.core.internal_auth_middleware import InternalAuthMiddleware

    upstream_calls = 0

    async def upstream() -> dict[str, object]:
        nonlocal upstream_calls
        upstream_calls += 1
        return {"type": "http.disconnect"}

    replay = InternalAuthMiddleware._replay(b"{}", upstream)  # noqa: SLF001 - 直接测这个私有静态方法

    first = await replay()
    assert first == {"type": "http.request", "body": b"{}", "more_body": False}
    assert upstream_calls == 0, "第一次必须吐 body"

    await replay()
    assert upstream_calls == 1, "之后必须转问真实 receive，而不是自己编一个断开"
