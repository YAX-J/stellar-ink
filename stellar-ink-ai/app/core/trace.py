"""traceId 贯穿：Java 侧 ``TraceIdFilter`` 的 Python 对应物。

约定（M1 会在此基础上加签名头）：
- Java 通过 ``X-Trace-Id`` 传入 traceId；没有则由 Python 生成。
- 响应回写同一个 ``X-Trace-Id``，便于跨语言日志对读。
- traceId 只进日志，不参与任何权限判断。
"""

import uuid
from collections.abc import Awaitable, Callable
from contextvars import ContextVar

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

TRACE_ID_HEADER = "X-Trace-Id"

_trace_id: ContextVar[str | None] = ContextVar("trace_id", default=None)


def current_trace_id() -> str | None:
    """取当前请求的 traceId；不在请求上下文中时返回 None。"""
    return _trace_id.get()


def new_trace_id() -> str:
    """生成 traceId（32 位十六进制，与 Java 侧 UUID 去横线写法一致）。"""
    return uuid.uuid4().hex


class TraceIdMiddleware(BaseHTTPMiddleware):
    """为每个请求绑定 traceId 生命周期。"""

    async def dispatch(
        self,
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        incoming = request.headers.get(TRACE_ID_HEADER)
        # 只接受短且字符安全的传入值，避免把任意客户端字符串塞进日志
        trace_id = incoming if incoming and len(incoming) <= 64 and incoming.isalnum() else None
        token = _trace_id.set(trace_id or new_trace_id())
        try:
            response = await call_next(request)
        finally:
            resolved = _trace_id.get() or ""
            _trace_id.reset(token)
        response.headers[TRACE_ID_HEADER] = resolved
        return response
