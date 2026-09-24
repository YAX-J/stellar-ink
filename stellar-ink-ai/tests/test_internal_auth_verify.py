"""内部请求验签：跨语言一致性、时间窗、防重放、fail-closed。

基准文件 `tests/fixtures/signature_vector.json` 由 Python 生成、**Java 侧同样读它**：
Java 用固定输入算出的签名必须与文件一致，Python 验签也必须接受同一签名 ——
「标准串格式」只有一份事实来源。

标准串含身份字段（userId/role）：只签 body 的话，内网中间人改一个头就能冒充 ADMIN。
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

import pytest

from app.core.internal_auth import (
    EMPTY_BODY_SHA256,
    HEADER_NONCE,
    HEADER_ROLE,
    HEADER_SIGNATURE,
    HEADER_TIMESTAMP,
    HEADER_TRACE_ID,
    HEADER_USER_ID,
    MIN_SECRET_LENGTH,
    InternalAuthError,
    InternalRequestVerifier,
    NonceStore,
    body_sha256,
    canonical_request,
    load_internal_secret,
    sign,
)

FIXTURES = Path(__file__).parent / "fixtures"


def load_vector() -> dict[str, Any]:
    with (FIXTURES / "signature_vector.json").open(encoding="utf-8") as handle:
        data: dict[str, Any] = json.load(handle)
    return data


def case_signature(case: dict[str, Any]) -> str:
    return str(case["signature"])


def headers_for(
    case: dict[str, Any],
    signature: str,
    *,
    user_id: str | None = None,
    role: str | None = None,
) -> dict[str, str]:
    identity = case["identity"]
    return {
        HEADER_SIGNATURE: signature,
        HEADER_TIMESTAMP: str(case["timestampMs"]),
        HEADER_NONCE: case["nonce"],
        HEADER_USER_ID: str(identity["userId"]) if user_id is None else user_id,
        HEADER_ROLE: identity["role"] if role is None else role,
        HEADER_TRACE_ID: "trace-from-java",
    }


def verifier() -> InternalRequestVerifier:
    return InternalRequestVerifier(load_vector()["secret"])


def canonical_of(case: dict[str, Any], body: str | None = None) -> str:
    identity = case["identity"]
    return canonical_request(
        case["method"],
        case["path"],
        case["timestampMs"],
        case["nonce"],
        body_sha256(case["body"] if body is None else body),
        identity["userId"],
        identity["role"],
    )


def test_vector_is_generated() -> None:
    for case in load_vector()["cases"]:
        assert "COMPUTED" not in case["signature"], (
            "向量未生成，请先跑 scripts/gen_signature_vector.py"
        )


@pytest.mark.parametrize("index", [0, 1, 2])
def test_verifies_signature_produced_for_same_canonical(index: int) -> None:
    """核心断言：Java 侧用同一标准串算出的签名，Python 必须接受。"""
    case = load_vector()["cases"][index]

    identity = verifier().verify(
        method=case["method"],
        path=case["path"],
        headers=headers_for(case, case_signature(case)),
        body=case["body"],
        now_ms=case["timestampMs"],
    )

    assert identity.user_id == case["identity"]["userId"]
    assert identity.role == case["identity"]["role"]
    assert identity.trace_id == "trace-from-java"


def test_python_recomputes_the_same_signature() -> None:
    """两侧算法一致：Python 重算的标准串与签名都要与向量逐字节相同。"""
    vector = load_vector()
    for case in vector["cases"]:
        assert body_sha256(case["body"]) == case["bodySha256"]
        canonical = canonical_of(case)
        assert canonical == case["canonical"], f"{case['name']} 的标准串不一致"
        assert sign(vector["secret"], canonical) == case["signature"]


def test_header_lookup_is_case_insensitive() -> None:
    """HTTP 头名大小写不敏感：httpx 发的是小写头名，Java 常量是 `X-AI-*`。

    用大小写敏感的 `dict(headers).get(NAME)` 会得到 None，表现为
    「请求明明带了签名，服务端却说没带」—— 日志里两头看起来都对，极难定位。
    """
    case = load_vector()["cases"][0]
    lowercased = {
        key.lower(): value for key, value in headers_for(case, case_signature(case)).items()
    }

    identity = verifier().verify(
        method=case["method"],
        path=case["path"],
        headers=lowercased,
        body=case["body"],
        now_ms=case["timestampMs"],
    )

    assert identity.user_id == case["identity"]["userId"]
    assert identity.role == case["identity"]["role"]


def test_verifier_uses_constant_time_comparison() -> None:
    """用 hmac.compare_digest 而不是 ==：避免逐字节比较泄露签名前缀。"""
    source = Path(__file__).resolve().parent.parent / "app" / "core" / "internal_auth.py"
    assert "compare_digest" in source.read_text(encoding="utf-8")


def test_rejects_tampered_body() -> None:
    case = load_vector()["cases"][0]

    with pytest.raises(InternalAuthError, match="签名不匹配"):
        verifier().verify(
            method=case["method"],
            path=case["path"],
            headers=headers_for(case, case_signature(case)),
            body=case["body"] + " 被改了",
            now_ms=case["timestampMs"],
        )


def test_rejects_tampered_identity() -> None:
    """篡改用户 id 必须失败：身份在签名里，改了就过不了验签。"""
    case = load_vector()["cases"][0]

    with pytest.raises(InternalAuthError, match="签名不匹配"):
        verifier().verify(
            method=case["method"],
            path=case["path"],
            headers=headers_for(case, case_signature(case), user_id="1"),
            body=case["body"],
            now_ms=case["timestampMs"],
        )


def test_rejects_privilege_escalation_by_role_header() -> None:
    """把角色改成 ADMIN 也必须失败 —— 这是「验签通过但身份是别人」的典型越权。"""
    case = load_vector()["cases"][0]

    with pytest.raises(InternalAuthError, match="签名不匹配"):
        verifier().verify(
            method=case["method"],
            path=case["path"],
            headers=headers_for(case, case_signature(case), role="ADMIN"),
            body=case["body"],
            now_ms=case["timestampMs"],
        )


def test_rejects_wrong_path_or_method() -> None:
    case = load_vector()["cases"][0]

    with pytest.raises(InternalAuthError, match="签名不匹配"):
        verifier().verify(
            method="GET",
            path=case["path"],
            headers=headers_for(case, case_signature(case)),
            body=case["body"],
            now_ms=case["timestampMs"],
        )


def test_rejects_expired_timestamp() -> None:
    case = load_vector()["cases"][0]
    too_late = case["timestampMs"] + 61_000

    with pytest.raises(InternalAuthError, match="时间戳超出允许窗口"):
        verifier().verify(
            method=case["method"],
            path=case["path"],
            headers=headers_for(case, case_signature(case)),
            body=case["body"],
            now_ms=too_late,
        )


def test_accepts_small_clock_drift() -> None:
    """双向容忍 60s：机器间时钟不可能完全一致，卡太死会让正常请求也 401。"""
    case = load_vector()["cases"][0]

    identity = verifier().verify(
        method=case["method"],
        path=case["path"],
        headers=headers_for(case, case_signature(case)),
        body=case["body"],
        now_ms=case["timestampMs"] + 30_000,
    )

    assert identity.user_id == case["identity"]["userId"]


def test_rejects_replayed_nonce() -> None:
    """同一个 nonce 第二次必须被拒（时间窗内的重放）。"""
    case = load_vector()["cases"][0]
    shared = InternalRequestVerifier(load_vector()["secret"])

    shared.verify(
        method=case["method"],
        path=case["path"],
        headers=headers_for(case, case_signature(case)),
        body=case["body"],
        now_ms=case["timestampMs"],
    )
    with pytest.raises(InternalAuthError, match="nonce 重复"):
        shared.verify(
            method=case["method"],
            path=case["path"],
            headers=headers_for(case, case_signature(case)),
            body=case["body"],
            now_ms=case["timestampMs"],
        )


def test_nonce_store_evicts_expired_entries() -> None:
    store = NonceStore(ttl_seconds=10)

    store.check_and_store("abc", now=0.0)
    store.check_and_store("abc", now=11.0)  # 已过期：另一个合法请求可以复用同一随机串
    assert store.size() == 1


def test_rejects_unknown_role_whitelist() -> None:
    """白名单外的角色（如伪造的 SUPER）不该被接受，即使签名正确。"""
    case = load_vector()["cases"][0]
    identity = case["identity"]
    forged = sign(
        load_vector()["secret"],
        canonical_request(
            case["method"],
            case["path"],
            case["timestampMs"],
            case["nonce"],
            body_sha256(case["body"]),
            identity["userId"],
            identity["role"],
        ),
    )

    with pytest.raises(InternalAuthError, match="未知角色"):
        verifier().verify(
            method=case["method"],
            path=case["path"],
            headers=headers_for(case, forged, role="SUPER"),
            body=case["body"],
            now_ms=case["timestampMs"],
        )


def test_missing_headers_are_rejected() -> None:
    case = load_vector()["cases"][0]

    for missing in (HEADER_SIGNATURE, HEADER_TIMESTAMP, HEADER_NONCE, HEADER_USER_ID):
        headers = headers_for(case, case_signature(case))
        del headers[missing]
        with pytest.raises(InternalAuthError):
            verifier().verify(
                method=case["method"],
                path=case["path"],
                headers=headers,
                body=case["body"],
                now_ms=case["timestampMs"],
            )


@pytest.mark.parametrize(
    ("method", "path", "timestamp", "nonce"),
    [
        ("GET", "health", 1, "n"),
        ("GET", "/health?x=1", 1, "n"),
        ("GET", "/health", 0, "n"),
        ("GET", "/health", 1, "bad\nnonce"),
        ("", "/health", 1, "n"),
    ],
)
def test_canonical_rejects_ambiguous_input(
    method: str, path: str, timestamp: int, nonce: str
) -> None:
    with pytest.raises(ValueError):
        canonical_request(method, path, timestamp, nonce, EMPTY_BODY_SHA256, 7, "READER")


@pytest.mark.parametrize(
    ("user_id", "role"),
    [(0, "READER"), (-1, "READER"), (7, "SUPER"), (7, "")],
)
def test_canonical_rejects_invalid_identity(user_id: int, role: str) -> None:
    with pytest.raises(ValueError):
        canonical_request("GET", "/health", 1, "n", EMPTY_BODY_SHA256, user_id, role)


def test_secret_problems_are_actionable() -> None:
    with pytest.raises(InternalAuthError):
        load_internal_secret({})
    with pytest.raises(InternalAuthError):
        load_internal_secret({"AI_INTERNAL_SECRET": "   "})
    with pytest.raises(InternalAuthError):
        load_internal_secret({"AI_INTERNAL_SECRET": "short"})
    with pytest.raises(InternalAuthError):
        InternalRequestVerifier("also-short")

    assert len(load_vector()["secret"]) >= MIN_SECRET_LENGTH


def test_body_digest_matches_java_definition() -> None:
    """空 body 用空串摘要：与 Java `CanonicalRequest.sha256Hex` 同口径。"""
    assert body_sha256(None) == EMPTY_BODY_SHA256
    assert body_sha256("") == EMPTY_BODY_SHA256
    assert body_sha256(b"") == EMPTY_BODY_SHA256
    assert body_sha256("星笺") == hashlib.sha256("星笺".encode()).hexdigest()
    # 中文走 UTF-8：两侧都显式指定编码，不能依赖平台默认值
    assert body_sha256("星笺") != body_sha256("星笺".encode("utf-16-le"))
