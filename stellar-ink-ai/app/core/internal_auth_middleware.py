"""把内部验签接到 FastAPI 上：受保护路径必须有合法签名，否则 401。

**为什么用纯 ASGI 中间件而不是 `@app.middleware("http")`**：
FastAPI/Starlette 的函数式中间件挂在路由**之后**（路由没匹配上会直接 404），
于是「不存在的路径」永远走不到验签 —— 那意味着任何新增路由在忘记加保护时
会先以 404/405 的形态暴露，而不是被拦在门外。纯 ASGI 中间件包在应用最外层，
在路由之前生效，因此**默认拒绝**是真的默认拒绝。

分工（与 `app/core/internal_auth.py` 配合）：
- 中间件做**一次**验签，把身份放进 `request.state`，业务依赖直接读，不重复算 HMAC；
- 公开路径（探活/文档）显式列出，其余一律要求签名；
- 验签失败在这里直接返回 401 JSON（不抛异常穿层），并记一条带 traceId 的警告日志。
"""

from __future__ import annotations

import json
import logging
from collections.abc import Awaitable, Callable, MutableMapping
from typing import Any, cast

from fastapi import Request

from app.core.internal_auth import (
    InternalAuthError,
    InternalIdentity,
    InternalRequestVerifier,
)
from app.core.trace import current_trace_id
from app.schemas.common import AiErrorCode

logger = logging.getLogger(__name__)

Scope = MutableMapping[str, Any]
Receive = Callable[[], Awaitable[dict[str, Any]]]
Send = Callable[[dict[str, Any]], Awaitable[None]]

#: 不需要内部签名的路径：探活与文档。
#: 探活是公开的（运维要能在未登录时判断服务是否活着），文档只在非 prod 暴露。
PUBLIC_PATHS: frozenset[str] = frozenset({"/health", "/docs", "/openapi.json", "/redoc"})

#: 内部身份的 state 键（业务依赖从这里读，避免各处猜属性名）
IDENTITY_STATE_KEY = "internal_identity"

#: 验签失败原因的 state 键（由依赖负责抛成 401）
ERROR_STATE_KEY = "internal_auth_error"


def is_public_path(path: str) -> bool:
    """公开路径判定：全等匹配，不做前缀匹配（`/health/x` 不该被放行）。"""
    return path in PUBLIC_PATHS


def _unauthorized_body(reason: str) -> bytes:
    return json.dumps(
        {
            "code": AiErrorCode.UNAUTHORIZED.value,
            "message": f"内部请求校验失败：{reason}",
            "traceId": current_trace_id(),
        },
        ensure_ascii=False,
    ).encode("utf-8")


class InternalAuthMiddleware:
    """纯 ASGI 中间件：路由之前完成验签，失败直接 401。"""

    def __init__(self, app: Any, verifier: InternalRequestVerifier | None) -> None:
        self._app = app
        self._verifier = verifier

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self._app(scope, receive, send)
            return

        path = scope.get("path", "")
        if is_public_path(path):
            await self._app(scope, receive, send)
            return

        if self._verifier is None:
            # 未配置密钥：明确拒绝而不是放行（fail-closed），提示写清怎么配
            await self._reject(send, "服务端未配置 AI_INTERNAL_SECRET，无法校验内部请求")
            return

        # 读全请求体用于验签，再把读到的数据「还」给下游，否则业务层拿不到 body
        body, more_body = await self._read_body(receive)
        if more_body:
            await self._reject(send, "请求体过大或未完整读取")
            return

        request = Request(scope, receive=self._replay(body))
        try:
            identity = self._verifier.verify(
                method=request.method,
                path=path,
                headers=dict(request.headers),
                body=body,
            )
            # 写进 request.state：Starlette 的 State 会落到 scope["state"]，
            # 下游同一个 Request 就能读到（不要直接改 scope，避免与其内部约定打架）
            request.state.__setattr__(IDENTITY_STATE_KEY, identity)
        except InternalAuthError as error:
            # 原因进日志（带 traceId，便于与 Java 侧对读），响应只给可展示提示
            logger.warning("内部验签失败：%s path=%s", error.reason, path)
            await self._reject(send, error.reason)
            return

        await self._app(scope, self._replay(body), send)

    @staticmethod
    async def _read_body(receive: Receive) -> tuple[bytes, bool]:
        chunks: list[bytes] = []
        while True:
            message = await receive()
            if message["type"] != "http.request":
                continue
            chunks.append(message.get("body", b""))
            if not message.get("more_body", False):
                return b"".join(chunks), False

    @staticmethod
    def _replay(body: bytes) -> Receive:
        """把已读出的 body 重新喂给下游（只发一次，之后返回断开）。"""
        sent = False

        async def receive() -> dict[str, Any]:
            nonlocal sent
            if sent:
                return {"type": "http.disconnect"}
            sent = True
            return {"type": "http.request", "body": body, "more_body": False}

        return cast(Receive, receive)

    @staticmethod
    async def _reject(send: Send, reason: str) -> None:
        """直接发一个 401 JSON 响应（不经过应用，避免被异常处理器再包一层）。"""
        body = _unauthorized_body(reason)
        await send(
            {
                "type": "http.response.start",
                "status": 401,
                "headers": [
                    (b"content-type", b"application/json; charset=utf-8"),
                    (b"content-length", str(len(body)).encode("ascii")),
                ],
            }
        )
        await send({"type": "http.response.body", "body": body})


def require_internal_identity(request: Request) -> InternalIdentity:
    """业务依赖：取验签后的身份；拿不到就抛 401（由全局处理器转成统一错误体）。"""
    error: InternalAuthError | None = getattr(request.state, ERROR_STATE_KEY, None)
    if error is not None:
        raise error
    identity: InternalIdentity | None = getattr(request.state, IDENTITY_STATE_KEY, None)
    if identity is None:
        # 走到这里说明有受保护路由没挂中间件（或中间件顺序错了）——
        # 报「未验签」而不是放行，宁可 401 也不要匿名通过
        raise InternalAuthError("请求未经内部验签")
    return identity
