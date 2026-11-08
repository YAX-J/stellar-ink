"""请求内用量账：实际调用记一次，嵌入缓存命中不记；并发请求互不串账。"""

from __future__ import annotations

from collections.abc import Awaitable, Callable, Coroutine, Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from functools import wraps
from typing import Any, ParamSpec, TypeVar

from app.providers.models import ProviderConfig, TokenUsage
from app.schemas.common import ModelCallUsage, Usage

P = ParamSpec("P")
R = TypeVar("R")
_calls: ContextVar[list[ModelCallUsage] | None] = ContextVar("model_usage", default=None)


@contextmanager
def collect_usage() -> Iterator[list[ModelCallUsage]]:
    existing = _calls.get()
    if existing is not None:
        yield existing
        return
    calls: list[ModelCallUsage] = []
    token = _calls.set(calls)
    try:
        yield calls
    finally:
        _calls.reset(token)


def checkpoint() -> int:
    return len(_calls.get() or [])


def record_usage(model: object, usage: TokenUsage | None, *, role: str = "chat") -> None:
    calls = _calls.get()
    if calls is None:
        return
    config = getattr(model, "config", None)
    configured = config if isinstance(config, ProviderConfig) else None
    known = usage is not None and usage.reported
    calls.append(
        ModelCallUsage(
            role=configured.role if configured else role,
            model=usage.model
            if usage and usage.model
            else configured.model
            if configured
            else None,
            provider=configured.provider if configured else None,
            config_id=configured.config_id if configured else None,
            config_user_id=configured.config_user_id if configured else 0,
            config_fingerprint=configured.fingerprint() if configured else None,
            prompt_tokens=usage.prompt_tokens if usage and known else None,
            completion_tokens=usage.completion_tokens if usage and known else None,
            total_tokens=max(usage.total_tokens, usage.prompt_tokens + usage.completion_tokens)
            if usage and known
            else None,
            latency_ms=usage.latency_ms if usage else 0,
            price_input=configured.price_input if configured else None,
            price_output=configured.price_output if configured else None,
        )
    )


def observe(model: object, usage: TokenUsage | None, before: int) -> None:
    """直接注入的模型也能计量；Provider 已记账时不重复累加。"""
    if checkpoint() == before:
        record_usage(model, usage)


def aggregate(calls: list[ModelCallUsage], previous: Usage | None = None) -> Usage:
    return Usage(
        prompt_tokens=sum(c.prompt_tokens or 0 for c in calls),
        completion_tokens=sum(c.completion_tokens or 0 for c in calls),
        total_tokens=sum(c.total_tokens or 0 for c in calls),
        latency_ms=previous.latency_ms if previous else sum(c.latency_ms for c in calls),
        model=previous.model
        if previous
        else next((c.model for c in reversed(calls) if c.role == "chat"), None),
        model_calls=list(calls),
        usage_complete=all(c.total_tokens is not None for c in calls),
        model_fingerprints=previous.model_fingerprints if previous else None,
    )


def metered(function: Callable[P, Awaitable[R]]) -> Callable[P, Coroutine[Any, Any, R]]:
    """包住实际 Provider 操作；放在拆批内层、缓存内层，避免虚增调用。"""

    @wraps(function)
    async def wrapped(*args: P.args, **kwargs: P.kwargs) -> R:
        result = await function(*args, **kwargs)
        record_usage(args[0], getattr(result, "usage", None))
        return result

    return wrapped


def usage_scope(function: Callable[P, Awaitable[R]]) -> Callable[P, Coroutine[Any, Any, R]]:
    @wraps(function)
    async def wrapped(*args: P.args, **kwargs: P.kwargs) -> R:
        with collect_usage() as calls:
            result = await function(*args, **kwargs)
            if hasattr(result, "usage"):
                target: Any = result
                target.usage = aggregate(calls, target.usage)
                fingerprints = getattr(args[0], "usage_fingerprints", None) if args else None
                if callable(fingerprints):
                    target.usage.model_fingerprints = fingerprints()
            return result

    return wrapped
