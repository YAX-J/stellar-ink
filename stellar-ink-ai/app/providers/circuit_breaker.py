"""断路（熔断）：重试之后的第三条护栏（M8）。

三条护栏各管一件事，别混：

* **退避重试**（`retry.py`）管「这一次」—— 429/5xx/超时值得再试一次；
* **嵌入缓存**（`embedding_cache.py`）管「同样的输入」—— 别重复花钱；
* **断路**管「这一段时间」—— 上游连续失败时**别再打了**。

为什么需要它（实测背景）：免费档 429 时，标准五组评测会**每一组、每一题**都去打同一个上游，
每一步都先等完退避再失败 —— 整轮评测的时间几乎全花在等待上，而上游一次都没成功过。
断路让「已经明确坏了的一段时间」直接快速失败，把等待省下来。

四条口径：

1. **配置类错误不进断路**（401/403、400 模型名写错）：它们不会自己好，
   而断路的意义是「等上游恢复」。把它们算进来只会让「去面板改 Key」这件事多隔一层迷雾。
2. **额度用尽进断路，但冷却时间要长**：免费档是「每模型每日 50 次」，
   退避几秒救不了 —— 用短的冷却时间只会让我们每 30 秒去打一次注定失败的请求。
3. **打开时抛的错误必须带上「上一次为什么失败」**：否则用户看到的是
   「服务暂不可用」，而真正的原因（额度用尽、模型名写错）被这一层吃掉了。
4. **半开只放一个探针**：冷却结束后放**一个**请求去试，成功才恢复。
   放一批进去就成了「冷却一过又打一批」，那正是要避免的事。
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

from app.providers.errors import (
    ProviderError,
    ProviderQuotaExhaustedError,
    ProviderUnavailableError,
)


class CircuitState(StrEnum):
    """断路状态。"""

    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


@dataclass(frozen=True, slots=True)
class CircuitBreakerConfig:
    """断路参数。"""

    #: 连续失败多少次打开（必须是**连续**：中间成功一次就清零）
    failure_threshold: int = 5
    #: 打开之后的冷却时间（秒）；冷却结束进入半开
    cooldown_s: float = 30.0
    #: 额度用尽时的冷却时间：它不是「上游在抖」，而是「今天别试了」
    quota_cooldown_s: float = 3600.0
    #: 半开失败后冷却时间的放大倍数（防止「冷却一过就失败」的抖动），有上限
    backoff_factor: float = 2.0
    max_cooldown_s: float = 1800.0

    def __post_init__(self) -> None:
        if self.failure_threshold < 1:
            raise ValueError("failure_threshold 至少为 1")
        if self.cooldown_s <= 0:
            raise ValueError("cooldown_s 必须为正")


class CircuitOpenError(ProviderUnavailableError):
    """断路打开时的错误。

    **不可重试**（`retryable=False`）：这一层的意义就是「别再打了」，
    如果它还可重试，重试逻辑会立刻把请求再送回来 —— 等于没熔断。
    """

    retryable = False

    def __init__(self, message: str, *, retry_after_s: float = 0.0) -> None:
        super().__init__(message, detail=f"建议 {retry_after_s:.0f} 秒后再试")
        self.retry_after_s = retry_after_s


@dataclass(slots=True)
class _Entry:
    """一个 key（通常是「角色 + 模型」）的状态。"""

    state: str = CircuitState.CLOSED
    consecutive_failures: int = 0
    opened_at: float = 0.0
    cooldown_s: float = 0.0
    #: 半开时是否已经有一个探针在飞（半开**只放一个**）
    probe_in_flight: bool = False
    last_message: str = ""
    opened_count: int = 0
    #: 供快照用：最近一次是哪种错误
    last_error_code: str = ""
    extra: dict[str, Any] = field(default_factory=dict)


class CircuitBreaker:
    """按 key 隔离的断路器。

    ⚠️ **按 key 隔离很重要**：`embedding` 挂了不该连累 `chat`。
    一个全局断路器会让「嵌入模型额度用尽」表现成「问答也不能用了」——
    而这两件事的处置（换嵌入模型 vs 换对话模型）完全不同。

    时间源可注入（`clock`）：单测必须能立刻跨过冷却时间，而不是真的睡 30 秒。
    并发安全：调用方是单事件循环的异步服务，状态是普通字段，不加锁
    （加锁会让人以为支持多线程，而它并不）。
    """

    def __init__(
        self,
        config: CircuitBreakerConfig | None = None,
        *,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._config = config or CircuitBreakerConfig()
        self._clock = clock
        self._entries: dict[str, _Entry] = {}

    @property
    def config(self) -> CircuitBreakerConfig:
        return self._config

    def allow(self, key: str) -> tuple[bool, float]:
        """能不能打这个 key。

        :returns `(是否放行, 建议等待秒数)`。等待秒数只在被拦下时有意义 ——
            调用方把它放进错误里，用户就知道「多久之后再试」，而不是盲目重试。
        """
        entry = self._entries.setdefault(key, _Entry())
        if entry.state == CircuitState.CLOSED:
            return True, 0.0
        if entry.state == CircuitState.OPEN:
            elapsed = self._clock() - entry.opened_at
            if elapsed < entry.cooldown_s:
                return False, entry.cooldown_s - elapsed
            # 冷却结束 → 半开，放**一个**探针
            entry.state = CircuitState.HALF_OPEN
            entry.probe_in_flight = True
            return True, 0.0
        # HALF_OPEN：只放一个；其余请求继续快速失败（等待时间给 0，因为它们该等的是探针结果）
        if entry.probe_in_flight:
            return False, 0.0
        entry.probe_in_flight = True
        return True, 0.0

    def record_success(self, key: str) -> None:
        """成功：关回 CLOSED 并把计数清零（半开的那次成功也走这里）。"""
        entry = self._entries.setdefault(key, _Entry())
        entry.state = CircuitState.CLOSED
        entry.consecutive_failures = 0
        entry.probe_in_flight = False
        entry.cooldown_s = 0.0
        entry.last_message = ""
        entry.last_error_code = ""

    def record_failure(self, key: str, error: BaseException) -> None:
        """失败：只有「值得等它恢复」的错误才计数（见模块 docstring）。"""
        entry = self._entries.setdefault(key, _Entry())
        was_probe = entry.state == CircuitState.HALF_OPEN
        entry.probe_in_flight = False
        entry.last_message = str(error)
        entry.last_error_code = type(error).__name__

        if not _counts_toward_circuit(error):
            # 配置类错误不进断路：它不会自己好，进熔断只会给「去改配置」多隔一层迷雾
            return

        entry.consecutive_failures += 1
        if isinstance(error, ProviderQuotaExhaustedError):
            # 额度用尽不是「在抖」：用长冷却（否则每 30 秒去打一次注定失败的请求）
            self._open(entry, self._config.quota_cooldown_s)
            return
        if was_probe:
            # 半开探针失败：冷却时间翻倍（有上限），避免「冷却一过就失败」的抖动
            self._open(
                entry,
                min(entry.cooldown_s * self._config.backoff_factor, self._config.max_cooldown_s),
            )
            return
        if entry.consecutive_failures >= self._config.failure_threshold:
            self._open(entry, self._config.cooldown_s)

    def state_of(self, key: str) -> str:
        entry = self._entries.get(key)
        return entry.state if entry else CircuitState.CLOSED

    def snapshot(self) -> dict[str, dict[str, Any]]:
        """给日志/观测用的只读快照（`/internal/trace` 那类出口可以带它）。"""
        return {
            key: {
                "state": entry.state,
                "consecutiveFailures": entry.consecutive_failures,
                "openedCount": entry.opened_count,
                "cooldownS": round(entry.cooldown_s, 1),
                "lastErrorCode": entry.last_error_code,
            }
            for key, entry in self._entries.items()
            if entry.state != CircuitState.CLOSED or entry.consecutive_failures
        }

    def _open(self, entry: _Entry, cooldown_s: float) -> None:
        entry.state = CircuitState.OPEN
        entry.opened_at = self._clock()
        entry.cooldown_s = cooldown_s
        entry.opened_count += 1

    def error_for(self, key: str, retry_after_s: float) -> CircuitOpenError:
        """被拦下时抛什么：**必须带上上一次失败的原因**。

        否则用户看到的是「服务暂不可用」，而真正的原因（额度用尽、模型名写错）
        被这一层吃掉了 —— 那正是「报错指向错误方向」的一种。
        """
        entry = self._entries.get(key)
        reason = entry.last_message if entry and entry.last_message else "上游连续失败"
        return CircuitOpenError(f"已暂停调用（原因：{reason}）", retry_after_s=retry_after_s)


def _counts_toward_circuit(error: BaseException) -> bool:
    """这个错误算不算「上游坏了」。

    算：可重试的（5xx/超时/连不上/瞬时限流）+ 额度用尽（今天别试了）。
    不算：配置类（鉴权失败、模型名写错）—— 它们不会自己好。
    """
    if isinstance(error, ProviderQuotaExhaustedError):
        return True
    if isinstance(error, ProviderError):
        return bool(error.retryable)
    return False
