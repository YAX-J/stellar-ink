"""断路（M8，`app/providers/circuit_breaker.py`）。

它与重试、嵌入缓存的分工是这一层要守的核心：

* 重试管「这一次」；缓存管「同样的输入」；**断路管「这一段时间」**。

所以要验的不是「失败会重试」（那是 retry 的事），而是：

1. 连续失败到阈值才打开（中间成功一次就清零）；
2. 打开期间**快速失败**，并且错误里带着「上一次为什么失败」——
   否则用户看到「服务暂不可用」，而真正的原因（额度用尽）被这一层吃掉了；
3. 冷却结束**只放一个探针**（放一批进去就成了「冷却一过又打一批」）；
4. **配置类错误不进断路**（401/400 不会自己好，进熔断只会给「去改配置」多隔一层迷雾）；
5. **额度用尽用长冷却**（免费档是每日 50 次，退避几秒救不了）；
6. 按 key 隔离（嵌入挂了不该连累对话）。
"""

from __future__ import annotations

import pytest

from app.providers.circuit_breaker import (
    CircuitBreaker,
    CircuitBreakerConfig,
    CircuitOpenError,
    CircuitState,
)
from app.providers.errors import (
    ProviderAuthError,
    ProviderError,
    ProviderQuotaExhaustedError,
    ProviderRateLimitError,
    ProviderUnavailableError,
)

KEY = "chat:deepseek-chat@https://api.example.com"


class _Clock:
    """可推进的时钟：单测必须能立刻跨过冷却，而不是真的睡 30 秒。"""

    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def _breaker(**kwargs: object) -> tuple[CircuitBreaker, _Clock]:
    clock = _Clock()
    config = CircuitBreakerConfig(**kwargs)  # type: ignore[arg-type]
    return CircuitBreaker(config, clock=clock), clock


def test_opens_after_consecutive_failures() -> None:
    breaker, _ = _breaker(failure_threshold=3)

    for _ in range(2):
        allowed, _ = breaker.allow(KEY)
        assert allowed is True
        breaker.record_failure(KEY, ProviderUnavailableError("模型服务返回 503"))

    assert breaker.state_of(KEY) == CircuitState.CLOSED, "还没到阈值，不该打开"

    breaker.record_failure(KEY, ProviderUnavailableError("模型服务返回 503"))

    assert breaker.state_of(KEY) == CircuitState.OPEN
    allowed, wait = breaker.allow(KEY)
    assert allowed is False
    assert wait > 0, "被拦下时要给出建议等待时间（否则用户只能盲目重试）"


def test_success_resets_the_counter() -> None:
    """只统计**连续**失败：中间成功一次就清零，否则「偶发失败」会攒成熔断。"""
    breaker, _ = _breaker(failure_threshold=3)

    breaker.record_failure(KEY, ProviderUnavailableError("503"))
    breaker.record_failure(KEY, ProviderUnavailableError("503"))
    breaker.record_success(KEY)
    breaker.record_failure(KEY, ProviderUnavailableError("503"))
    breaker.record_failure(KEY, ProviderUnavailableError("503"))

    assert breaker.state_of(KEY) == CircuitState.CLOSED


def test_open_error_carries_the_real_reason() -> None:
    """**打开时抛的错误必须带上真因**，否则「去改配置」这件事会被这层迷雾挡住。"""
    breaker, _ = _breaker(failure_threshold=1)
    breaker.record_failure(KEY, ProviderQuotaExhaustedError("免费档今日 50 次已用尽，次日零点重置"))

    allowed, wait = breaker.allow(KEY)
    error = breaker.error_for(KEY, wait)

    assert allowed is False
    assert "次日零点重置" in str(error), str(error)
    assert error.retryable is False, "熔断错误不能再可重试 —— 否则重试逻辑立刻把它送回来"


def test_half_open_allows_only_one_probe() -> None:
    """冷却结束只放**一个**探针：放一批进去就是「冷却一过又打一批」。"""
    breaker, clock = _breaker(failure_threshold=1, cooldown_s=30)
    breaker.record_failure(KEY, ProviderUnavailableError("503"))

    clock.advance(31)
    first, _ = breaker.allow(KEY)
    second, _ = breaker.allow(KEY)

    assert first is True, "冷却结束后要放一个探针去试"
    assert second is False, "探针还在飞的时候，其余请求继续快速失败"
    assert breaker.state_of(KEY) == CircuitState.HALF_OPEN


def test_probe_success_closes_the_circuit() -> None:
    breaker, clock = _breaker(failure_threshold=1, cooldown_s=30)
    breaker.record_failure(KEY, ProviderUnavailableError("503"))
    clock.advance(31)
    breaker.allow(KEY)

    breaker.record_success(KEY)

    assert breaker.state_of(KEY) == CircuitState.CLOSED
    allowed, _ = breaker.allow(KEY)
    assert allowed is True


def test_probe_failure_doubles_the_cooldown() -> None:
    """半开探针失败要**延长**冷却：否则会变成「每 30 秒失败一次」的抖动。"""
    breaker, clock = _breaker(failure_threshold=1, cooldown_s=30, max_cooldown_s=600)
    breaker.record_failure(KEY, ProviderUnavailableError("503"))
    clock.advance(31)
    breaker.allow(KEY)
    breaker.record_failure(KEY, ProviderUnavailableError("503"))

    _, wait = breaker.allow(KEY)

    assert wait > 30, f"冷却时间应当被放大：{wait}"
    assert wait <= 600, "放大要有上限"


def test_configuration_errors_do_not_open_the_circuit() -> None:
    """401/400 不会自己好：进熔断只会给「去面板改 Key」多隔一层迷雾。"""
    breaker, _ = _breaker(failure_threshold=1)

    breaker.record_failure(KEY, ProviderAuthError("模型密钥无效或无权限"))
    breaker.record_failure(KEY, ProviderError("模型服务拒绝了请求（HTTP 400）"))

    assert breaker.state_of(KEY) == CircuitState.CLOSED
    allowed, _ = breaker.allow(KEY)
    assert allowed is True


def test_quota_exhausted_gets_a_long_cooldown() -> None:
    """额度用尽是「今天别试了」，不是「上游在抖」：短冷却等于每 30 秒打一次注定失败的请求。"""
    breaker, _ = _breaker(failure_threshold=1, cooldown_s=30, quota_cooldown_s=3600)

    breaker.record_failure(KEY, ProviderQuotaExhaustedError("今日额度用尽"))

    _, wait = breaker.allow(KEY)

    assert wait > 3000, f"额度用尽的冷却要接近一天量级：{wait}"


def test_transient_rate_limit_counts_but_needs_the_threshold() -> None:
    breaker, _ = _breaker(failure_threshold=2)

    breaker.record_failure(KEY, ProviderRateLimitError("请求过于频繁"))

    assert breaker.state_of(KEY) == CircuitState.CLOSED, "一次瞬时限流不该立刻熔断"
    breaker.record_failure(KEY, ProviderRateLimitError("请求过于频繁"))
    assert breaker.state_of(KEY) == CircuitState.OPEN


def test_keys_are_isolated() -> None:
    """嵌入挂了不该连累对话（两者的处置完全不同：换嵌入模型 vs 换对话模型）。"""
    breaker, _ = _breaker(failure_threshold=1)
    chat_key = "chat:deepseek-chat@https://api.example.com"
    embed_key = "embedding:bge-m3@https://api.siliconflow.cn"

    breaker.record_failure(embed_key, ProviderUnavailableError("503"))

    assert breaker.state_of(embed_key) == CircuitState.OPEN
    assert breaker.state_of(chat_key) == CircuitState.CLOSED
    allowed, _ = breaker.allow(chat_key)
    assert allowed is True


def test_snapshot_reports_only_noteworthy_keys() -> None:
    """快照给观测出口用：健康的 key 不该把输出撑满。"""
    breaker, _ = _breaker(failure_threshold=2)
    breaker.record_success("chat:ok@https://api.example.com")
    breaker.record_failure(KEY, ProviderUnavailableError("503"))

    snapshot = breaker.snapshot()

    assert KEY in snapshot
    assert snapshot[KEY]["consecutiveFailures"] == 1
    assert "chat:ok@https://api.example.com" not in snapshot, "健康且无失败的 key 不出现"


def test_invalid_config_is_rejected() -> None:
    with pytest.raises(ValueError):
        CircuitBreakerConfig(failure_threshold=0)
    with pytest.raises(ValueError):
        CircuitBreakerConfig(cooldown_s=0)


async def test_provider_fails_fast_when_circuit_is_open() -> None:
    """接进 provider 之后：熔断打开时**连第一次尝试都不做**（否则「熔断」只是少重试几次）。"""
    import httpx

    from app.providers.circuit_breaker import CircuitBreaker as Breaker
    from app.providers.models import (
        ChatMessage,
        MessageRole,
        ProviderCapabilities,
        ProviderConfig,
    )
    from app.providers.openai_compatible import OpenAICompatibleProvider

    config = ProviderConfig(
        role="chat",
        provider="openai_compatible",
        base_url="https://example.invalid/v1",
        model="breaker-chat",
        api_key="sk-test",
        capabilities=ProviderCapabilities(chat=True),
    )
    calls = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        del request
        calls["count"] += 1
        return httpx.Response(503, json={"error": {"message": "upstream down"}})

    breaker = Breaker(CircuitBreakerConfig(failure_threshold=1))
    provider = OpenAICompatibleProvider(
        config,
        transport=httpx.MockTransport(handler),
        breaker=breaker,
        # 重试要立刻发生：否则这个用例要等好几秒的退避
        sleeper=lambda _seconds: _noop(),
    )

    with pytest.raises(ProviderUnavailableError):
        await provider.chat([ChatMessage(MessageRole.USER, "你好")], max_tokens=8)
    first_round = calls["count"]
    assert first_round > 0

    # 第二次：熔断已打开 → 不该再打上游
    with pytest.raises(CircuitOpenError):
        await provider.chat([ChatMessage(MessageRole.USER, "你好")], max_tokens=8)

    assert calls["count"] == first_round, "熔断打开后不该再产生任何上游调用"
    await provider.aclose()


async def _noop() -> None:
    return None
