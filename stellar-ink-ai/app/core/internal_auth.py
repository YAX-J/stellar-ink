"""内部请求验签：确认「这些头确实由持有 AI_INTERNAL_SECRET 的 Java 侧签发」。

为什么内网也要验签：编排网络里任何能发 HTTP 的进程都能伪造 `X-AI-User-Id`，
签名把「必须持有密钥」变成伪造前提；时间窗与 nonce 让截获的请求无法原样重放。

标准串与 Java 侧 `CanonicalRequest.of` 必须逐字节一致，一致性由
`tests/fixtures/signature_vector.json` 的固定向量守住（两侧单测都读它）：

    METHOD \n PATH \n TIMESTAMP_MS \n NONCE \n SHA256_HEX(BODY) \n USER_ID \n ROLE

**身份字段也在签名里**：只签 body 的话，内网中间人把 `X-AI-User-Id` 改成 1（ADMIN）
依然能通过校验 —— 那是「验签通过但身份是别人」，比不验签更危险。

设计取舍：
- nonce 防重放用**进程内**存储 + TTL（够用且不引入依赖）；将来多实例部署时换成 Redis，
  但**不能**因为存储不可用就放行 —— 与网关的 JWT 撤销检查一样是 fail-closed。
- 校验顺序固定为「时间戳 → 签名 → nonce」：先做便宜的检查，
  但**签名必须在 nonce 之前**，否则攻击者可以用垃圾签名把 nonce 表刷满。
"""

from __future__ import annotations

import hashlib
import hmac
import time
from dataclasses import dataclass, field

#: 内部签名密钥的环境变量名（与 Java `InternalRequestSigner.SECRET_ENV` 一致）
SECRET_ENV = "AI_INTERNAL_SECRET"

#: 与 Java `AiInternalHeaders` 对应的头名
HEADER_USER_ID = "X-AI-User-Id"
HEADER_ROLE = "X-AI-Role"
HEADER_TIMESTAMP = "X-AI-Timestamp"
HEADER_NONCE = "X-AI-Nonce"
HEADER_SIGNATURE = "X-AI-Signature"
HEADER_TRACE_ID = "X-Trace-Id"

#: 允许的时间偏移（秒）：与 Java 侧 60_000ms 对齐，双向容忍时钟漂移
TIMESTAMP_TOLERANCE_SECONDS = 60

#: nonce 表的保留时长：至少覆盖时间窗，否则窗口内的重放会因过期而被误放行
NONCE_TTL_SECONDS = TIMESTAMP_TOLERANCE_SECONDS * 2

#: 空请求体摘要（SHA-256("")）
EMPTY_BODY_SHA256 = hashlib.sha256(b"").hexdigest()

#: 密钥最小长度（与 Java `MIN_SECRET_LENGTH` 一致）
MIN_SECRET_LENGTH = 32

VALID_ROLES = frozenset({"READER", "AUTHOR", "ADMIN"})


class InternalAuthError(RuntimeError):
    """验签失败。`reason` 只用于服务端日志，**不回给调用方**（避免暴露校验细节）。"""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def load_internal_secret(env: dict[str, str] | None = None) -> str:
    """读取内部签名密钥；缺失或过短时抛错（与 Java 侧同口径）。"""
    import os

    source = os.environ if env is None else env
    raw = (source.get(SECRET_ENV) or "").strip()
    if not raw:
        raise InternalAuthError(f"未配置 {SECRET_ENV}，无法校验内部请求")
    if len(raw) < MIN_SECRET_LENGTH:
        raise InternalAuthError(f"{SECRET_ENV} 长度不足 {MIN_SECRET_LENGTH} 字符")
    return raw


def body_sha256(body: bytes | str | None) -> str:
    """请求体摘要：空 body 用空串的摘要，与 Java 侧一致。"""
    if body is None:
        raw = b""
    elif isinstance(body, str):
        raw = body.encode("utf-8")
    else:
        raw = body
    return hashlib.sha256(raw).hexdigest()


def canonical_request(
    method: str,
    path: str,
    timestamp_ms: int,
    nonce: str,
    body_digest: str,
    user_id: int | str,
    role: str,
) -> str:
    """组装标准串。

    **身份（user_id / role）必须参与签名**：签名如果只覆盖 body，内网里能改包的一方
    就能伪造 `X-AI-User-Id: 1`（ADMIN）拿到任意数据 —— 那是「验签通过但身份是别人」，
    比不验签更危险。字段顺序与分隔符必须与 Java 侧 `CanonicalRequest.of` 一致：

        METHOD \\n PATH \\n TIMESTAMP_MS \\n NONCE \\n BODY_SHA256 \\n USER_ID \\n ROLE
    """
    if not method or not path or not nonce or not body_digest:
        raise ValueError("canonical 的字段都不能为空")
    if not path.startswith("/") or "?" in path:
        raise ValueError(f"path 必须以 / 开头且不含 query：{path}")
    if any("\n" in field for field in (method, path, nonce, body_digest, role)):
        raise ValueError("canonical 的字段不能包含换行")
    if timestamp_ms <= 0:
        raise ValueError("timestampMs 必须是正的 Unix 毫秒时间戳")
    identity = str(user_id).strip()
    if not identity.isdigit() or int(identity) <= 0:
        raise ValueError(f"user_id 必须是正整数：{user_id}")
    normalized_role = (role or "").strip().upper()
    if normalized_role not in VALID_ROLES:
        raise ValueError(f"角色必须在白名单内：{role}")
    fields = [
        method.upper(),
        path,
        str(timestamp_ms),
        nonce,
        body_digest.lower(),
        identity,
        normalized_role,
    ]
    return "\n".join(fields)


def sign(secret: str, canonical: str) -> str:
    """HMAC-SHA256，小写十六进制（供测试与本地调试使用；Java 侧是签发方）。"""
    return hmac.new(secret.encode("utf-8"), canonical.encode("utf-8"), hashlib.sha256).hexdigest()


@dataclass
class NonceStore:
    """进程内 nonce 表：`nonce → 过期时刻`。同一 nonce 在 TTL 内出现第二次即拒绝重放。"""

    ttl_seconds: int = NONCE_TTL_SECONDS
    _seen: dict[str, float] = field(default_factory=dict)

    def check_and_store(self, nonce: str, *, now: float | None = None) -> None:
        current = time.monotonic() if now is None else now
        self._evict(current)
        if nonce in self._seen:
            raise InternalAuthError(f"nonce 重复（疑似重放）：{nonce[:8]}…")
        self._seen[nonce] = current + self.ttl_seconds

    def _evict(self, now: float) -> None:
        expired = [key for key, deadline in self._seen.items() if deadline <= now]
        for key in expired:
            del self._seen[key]

    def size(self) -> int:
        return len(self._seen)


@dataclass(frozen=True)
class InternalIdentity:
    """验签通过后的调用方身份。"""

    user_id: int
    role: str
    trace_id: str | None


class InternalRequestVerifier:
    """校验内部请求的身份头；失败一律抛 `InternalAuthError`（fail-closed）。"""

    def __init__(self, secret: str, *, nonce_store: NonceStore | None = None) -> None:
        if len(secret) < MIN_SECRET_LENGTH:
            raise InternalAuthError(f"内部签名密钥长度不足 {MIN_SECRET_LENGTH} 字符")
        self._secret = secret
        self._nonces = nonce_store if nonce_store is not None else NonceStore()

    def verify(
        self,
        *,
        method: str,
        path: str,
        headers: dict[str, str],
        body: bytes | str | None = None,
        now_ms: int | None = None,
    ) -> InternalIdentity:
        """校验并返回身份；任何一步失败都抛异常，绝不返回「部分可信」的结果。"""
        signature = (headers.get(HEADER_SIGNATURE) or "").strip().lower()
        if not signature:
            raise InternalAuthError(f"缺少 {HEADER_SIGNATURE} 头")

        timestamp_raw = (headers.get(HEADER_TIMESTAMP) or "").strip()
        nonce = (headers.get(HEADER_NONCE) or "").strip()
        user_id_raw = (headers.get(HEADER_USER_ID) or "").strip()
        role = (headers.get(HEADER_ROLE) or "").strip().upper()
        if not timestamp_raw or not nonce or not user_id_raw:
            raise InternalAuthError("缺少时间戳、nonce 或用户身份头")

        # 1) 时间戳窗口：过期的请求直接拒绝，不进入昂贵的比对
        try:
            timestamp_ms = int(timestamp_raw)
        except ValueError as exc:
            raise InternalAuthError("时间戳不是整数") from exc
        current_ms = int(time.time() * 1000) if now_ms is None else now_ms
        drift_ms = abs(current_ms - timestamp_ms)
        if drift_ms > TIMESTAMP_TOLERANCE_SECONDS * 1000:
            raise InternalAuthError(f"时间戳超出允许窗口（偏差 {drift_ms}ms）")

        # 2) 身份与参数一起参与签名：先解析出 user_id/role（它们是签名材料的一部分）
        try:
            user_id = int(user_id_raw)
        except ValueError as exc:
            raise InternalAuthError("用户 id 不是整数") from exc
        if user_id <= 0:
            raise InternalAuthError("用户 id 必须是正整数")
        if role not in VALID_ROLES:
            # 角色只接受白名单：伪造一个 "SUPER" 之类的角色不该被当成更高权限
            raise InternalAuthError(f"未知角色：{role}")

        expected = sign(
            self._secret,
            canonical_request(method, path, timestamp_ms, nonce, body_sha256(body), user_id, role),
        )
        if not hmac.compare_digest(expected, signature):
            raise InternalAuthError("签名不匹配")

        # 3) nonce 防重放：放在签名之后，避免垃圾签名把 nonce 表刷满
        self._nonces.check_and_store(nonce)

        return InternalIdentity(
            user_id=user_id,
            role=role,
            trace_id=headers.get(HEADER_TRACE_ID) or None,
        )
