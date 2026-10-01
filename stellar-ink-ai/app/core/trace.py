"""traceId 贯穿：Java 侧 ``TraceIdFilter`` 的 Python 对应物。

约定（M1 会在此基础上加签名头）：
- Java 通过 ``X-Trace-Id`` 传入 traceId；没有则由 Python 生成。
- 响应回写同一个 ``X-Trace-Id``，便于跨语言日志对读。
- traceId 只进日志，不参与任何权限判断。

E3-4 起这里还维护一份**进程内的 trace 事件缓冲**（``record_event`` / ``trace_snapshot``）：
一个 traceId 就能看到「检索做了什么、调了哪些工具、模型花了多少 token」，
而不用把三处日志按时间戳手工拼起来。

三处刻意的口径：

1. **只存结构，不存内容**。事件里放的是「查了几次、命中几段、多少 token、耗时多少」这类
   计数与标识；**提示词、草稿、答案、正文片段一律不进**（``_FORBIDDEN_KEYS`` 会直接抛错，
   而不是静默截断）。要排障的人需要的是链路形状，不是用户内容 —— 而后者一旦进缓冲，
   就等于在内存里又留了一份隐私数据。
2. **有界**：最多 ``MAX_TRACES`` 个 trace、每个 trace ``MAX_EVENTS`` 条事件，超出丢最旧的。
   没有上限的环形缓冲在长跑的服务里就是一条内存泄漏。
3. **进程内**：多副本部署时，一个 traceId 可能只在某一台上查得到 —— ``found=false`` 就是这个意思，
   不是「这条链路不存在」。要跨副本就得集中存储（OTel / Langfuse），那是需要单独拍板的部署决定。
"""

import logging
import time
import uuid
from collections import OrderedDict
from collections.abc import Awaitable, Callable, Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

logger = logging.getLogger(__name__)

TRACE_ID_HEADER = "X-Trace-Id"

_trace_id: ContextVar[str | None] = ContextVar("trace_id", default=None)

#: 缓冲的 trace 条数上限（按最近使用淘汰）
MAX_TRACES = 200
#: 单个 trace 的事件条数上限（保留最新的）
MAX_EVENTS = 120
#: 事件里单个字符串值的长度上限（超了截断；标识类字段本来就该是短的）
MAX_VALUE_CHARS = 120
#: 绝不允许进缓冲的字段：它们装的是「用户内容」，不是链路结构
_FORBIDDEN_KEYS = frozenset(
    {"prompt", "messages", "draft", "answer", "content", "question", "text", "snippet", "body"}
)


@dataclass(frozen=True, slots=True)
class TraceEvent:
    """一条链路事件。`fields` 只放结构（计数 / 标识 / 耗时），见模块 docstring。"""

    at_ms: int
    kind: str
    fields: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {"atMs": self.at_ms, "kind": self.kind, **self.fields}


#: traceId → 事件列表（按插入顺序）。用 OrderedDict 是为了「最近使用」淘汰时能 O(1) 取最旧
_events: OrderedDict[str, list[TraceEvent]] = OrderedDict()

#: traceId → 第一次写入的单调时间（用于快照里报告这条链路有多长）
_started: dict[str, int] = {}


def current_trace_id() -> str | None:
    """取当前请求的 traceId；不在请求上下文中时返回 None。"""
    return _trace_id.get()


def new_trace_id() -> str:
    """生成 traceId（32 位十六进制，与 Java 侧 UUID 去横线写法一致）。"""
    return uuid.uuid4().hex


@contextmanager
def bind_trace(trace_id: str) -> Iterator[None]:
    """在**没有 HTTP 请求**的场景里摆出 trace 上下文（脚本、契约测试、探索性排查）。

    公开它而不是让调用方去摸 `_trace_id` 那个私有 ContextVar：脚本里 import 私有名
    一旦被重命名就会静默失效（`record_event` 在没有 traceId 时是**跳过**而不是报错），
    于是「生成 fixture 的脚本只写出一个空文件」这种错很难看出来。
    """
    token = _trace_id.set(trace_id)
    try:
        yield
    finally:
        _trace_id.reset(token)


def record_event(kind: str, **fields: Any) -> None:
    """记一条链路事件。

    **没有 traceId 时静默跳过**：离线脚本与单测里没有请求上下文，那不是错误。
    但字段违规（见 `_FORBIDDEN_KEYS`）**必须抛错** —— 静默丢字段会让人以为「已经记下来了」，
    而真相是那条事件永远缺了最有用的那一段。
    """
    trace_id = current_trace_id()
    if not trace_id:
        return
    clean: dict[str, Any] = {}
    for key, value in fields.items():
        if key in _FORBIDDEN_KEYS:
            raise ValueError(f"trace 事件不得记录用户内容字段：{key}（{kind}）")
        if value is None:
            continue
        if isinstance(value, str) and len(value) > MAX_VALUE_CHARS:
            value = value[:MAX_VALUE_CHARS] + "…"
        clean[key] = value

    event = TraceEvent(at_ms=now_ms(), kind=kind, fields=clean)
    bucket = _events.get(trace_id)
    if bucket is None:
        bucket = []
        _events[trace_id] = bucket
        _started[trace_id] = event.at_ms
        _evict_oldest_traces()
    else:
        _events.move_to_end(trace_id)
    bucket.append(event)
    if len(bucket) > MAX_EVENTS:
        # 保留最新的：排障看的是「这次到底发生了什么」，最旧的那些最先失去价值
        del bucket[: len(bucket) - MAX_EVENTS]


def trace_snapshot(trace_id: str) -> dict[str, Any] | None:
    """按 traceId 取回事件；**查不到返回 None**（进程内缓冲，多副本或太久之前都会查不到）。"""
    bucket = _events.get(trace_id)
    if bucket is None:
        return None
    _events.move_to_end(trace_id)
    return {
        "traceId": trace_id,
        "startedAtMs": _started.get(trace_id),
        "events": [event.to_dict() for event in bucket],
    }


def reset_traces() -> None:
    """清空缓冲（测试与「想知道现在开始发生什么」时用）。"""
    _events.clear()
    _started.clear()


def now_ms() -> int:
    return int(time.time() * 1000)


def _evict_oldest_traces() -> None:
    while len(_events) > MAX_TRACES:
        oldest, _ = _events.popitem(last=False)
        _started.pop(oldest, None)


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
