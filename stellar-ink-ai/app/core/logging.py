"""日志：单行结构化输出，并带上当前请求的 traceId。

为什么要自己写 formatter 而不是用 JSON 日志库：只需一个字段（traceId 贯穿），
不值得为此新增依赖；格式与 Java 侧 ``TraceIdFilter`` + 访问日志保持可对读。
"""

import json
import logging
import sys
from typing import Any

from app.core.trace import current_trace_id

_RESERVED = frozenset(
    {
        "args",
        "asctime",
        "created",
        "exc_info",
        "exc_text",
        "filename",
        "funcName",
        "levelname",
        "levelno",
        "lineno",
        "module",
        "msecs",
        "message",
        "msg",
        "name",
        "pathname",
        "process",
        "processName",
        "relativeCreated",
        "stack_info",
        "thread",
        "threadName",
        "taskName",
    }
)


class TraceIdFormatter(logging.Formatter):
    """把 traceId 与 ``extra`` 中非保留字段平铺进单行 JSON。"""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        trace_id = getattr(record, "trace_id", None) or current_trace_id()
        if trace_id:
            payload["traceId"] = trace_id
        for key, value in record.__dict__.items():
            if key not in _RESERVED and key != "trace_id" and not key.startswith("_"):
                payload[key] = value
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


def configure_logging(level: str = "INFO") -> None:
    """配置根 logger。重复调用是幂等的（覆盖 handler 而不是叠加）。"""
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(TraceIdFormatter())

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level)

    # uvicorn 自带的 access/error logger 交回根 logger，避免双份输出与格式不一致
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        logger = logging.getLogger(name)
        logger.handlers.clear()
        logger.propagate = True
