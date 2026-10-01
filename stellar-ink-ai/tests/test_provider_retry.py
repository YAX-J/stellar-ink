"""退避重试：只重试**值得重试**的失败，且次数与等待都有上限。

这一层要守的是那条实测教训：一次 429 不该毁掉整轮评测（曾经让 30 道题全被降级成「拒答」，
在对比表上看起来像「开了重排就彻底失效」）。同时要守住反面：
401/400 这类**改配置才能解决**的错误一次都不该重试（重试只会让用户多等）。
"""

from __future__ import annotations

from datetime import datetime

import httpx
import pytest

from app.providers.errors import (
    ProviderAuthError,
    ProviderError,
    ProviderQuotaExhaustedError,
    ProviderRateLimitError,
    ProviderUnavailableError,
)
from app.providers.models import ChatMessage, MessageRole, ProviderCapabilities, ProviderConfig
from app.providers.openai_compatible import OpenAICompatibleProvider
from app.providers.retry import RetryPolicy

CONFIG = ProviderConfig(
    role="chat",
    provider="openai_compatible",
    base_url="https://example.invalid/v1",
    model="retry-chat",
    api_key="sk-test",
    capabilities=ProviderCapabilities(chat=True),
)

OK_BODY = {
    "choices": [{"message": {"content": "好"}, "finish_reason": "stop"}],
    "usage": {"prompt_tokens": 1, "completion_tokens": 1},
}


class _Recorder:
    """按脚本依次返回状态码的假上游（用完最后一个就一直用它）。"""

    def __init__(self, statuses: list[int]) -> None:
        self.statuses = statuses
        self.calls = 0

    def handler(self, request: httpx.Request) -> httpx.Response:
        del request
        status = self.statuses[min(self.calls, len(self.statuses) - 1)]
        self.calls += 1
        if status == 200:
            return httpx.Response(200, json=OK_BODY)
        return httpx.Response(status, json={"error": "upstream"})

    def provider(self, policy: RetryPolicy | None = None) -> tuple[OpenAICompatibleProvider, list]:
        slept: list[float] = []

        async def sleeper(seconds: float) -> None:
            slept.append(seconds)

        return (
            OpenAICompatibleProvider(
                CONFIG,
                transport=httpx.MockTransport(self.handler),
                retry=policy,
                sleeper=sleeper,
            ),
            slept,
        )


async def _ask(provider: OpenAICompatibleProvider) -> None:
    await provider.chat([ChatMessage(MessageRole.USER, "你好")])


def test_policy_backoff_is_capped_and_exponential() -> None:
    policy = RetryPolicy(attempts=5, base_delay_ms=1000, max_delay_ms=3000)

    assert policy.delay_for(1) == 1.0
    assert policy.delay_for(2) == 2.0
    assert policy.delay_for(3) == 3.0, "超过上限之后不再翻倍 —— 否则 attempts 一调大就等到天荒地老"
    assert policy.delay_for(4) == 3.0


def test_policy_only_retries_retryable_errors() -> None:
    policy = RetryPolicy()

    assert policy.should_retry(ProviderRateLimitError("429"))
    assert policy.should_retry(ProviderUnavailableError("502"))
    assert not policy.should_retry(ProviderAuthError("401"))
    assert not policy.should_retry(ProviderError("400"))


async def test_rate_limit_then_success_is_retried() -> None:
    """一次 429 之后成功：调用方**不该看到任何异常**。"""
    recorder = _Recorder([429, 200])
    provider, slept = recorder.provider(RetryPolicy(attempts=3, base_delay_ms=10))

    response = await provider.chat([ChatMessage(MessageRole.USER, "你好")])

    assert response.text == "好"
    assert recorder.calls == 2, "第二次尝试应当真的打到上游"
    assert slept == [0.01], "重试前要退避（这里被替换成即时）"
    await provider.aclose()


async def test_rate_limit_exhausts_attempts_then_raises() -> None:
    recorder = _Recorder([429])
    provider, slept = recorder.provider(RetryPolicy(attempts=3, base_delay_ms=10))

    with pytest.raises(ProviderRateLimitError):
        await _ask(provider)

    assert recorder.calls == 3, "恰好尝试 attempts 次，不多不少"
    assert len(slept) == 2, "最后一次失败后不再等待（已经没有下一次了）"
    await provider.aclose()


async def test_server_error_is_retried() -> None:
    recorder = _Recorder([503, 200])
    provider, _ = recorder.provider(RetryPolicy(attempts=2, base_delay_ms=1))

    await _ask(provider)

    assert recorder.calls == 2
    await provider.aclose()


async def test_auth_error_is_not_retried() -> None:
    """401 要的是去改配置：重试只会让用户白等三倍时间。"""
    recorder = _Recorder([401])
    provider, slept = recorder.provider(RetryPolicy(attempts=3, base_delay_ms=10))

    with pytest.raises(ProviderAuthError):
        await _ask(provider)

    assert recorder.calls == 1
    assert slept == []
    await provider.aclose()


async def test_bad_request_is_not_retried() -> None:
    """400（模型名写错一类）同样不重试。"""
    recorder = _Recorder([400])
    provider, _ = recorder.provider(RetryPolicy(attempts=3, base_delay_ms=10))

    with pytest.raises(ProviderError) as info:
        await _ask(provider)

    assert recorder.calls == 1
    assert "模型名不存在" in str(info.value), "可操作的提示必须留在错误里"
    await provider.aclose()


async def test_disabled_policy_means_no_retry() -> None:
    recorder = _Recorder([429])
    provider, slept = recorder.provider(RetryPolicy.disabled())

    with pytest.raises(ProviderRateLimitError):
        await _ask(provider)

    assert recorder.calls == 1
    assert slept == []
    await provider.aclose()


async def test_daily_quota_is_not_retried_and_says_when_it_resets() -> None:
    """**额度用尽 ≠ 瞬时限流**：这是本轮实测到的真实形态（免费档 50 次/日）。

    如果把它当瞬时限流，表现是「退避三次、每次几秒，然后仍然失败」，
    而错误信息只说「稍后重试」—— 让人白折腾一天。所以它必须：① 不重试；
    ② 说清什么时候重置、现在该做什么。
    """
    reset_ms = int((datetime.now().timestamp() + 8 * 3600) * 1000)

    def handler(request: httpx.Request) -> httpx.Response:
        del request
        return httpx.Response(
            429,
            headers={
                "x-ratelimit-limit": "50",
                "x-ratelimit-remaining": "0",
                "x-ratelimit-reset": str(reset_ms),
            },
            json={"error": {"message": "free-models-per-day"}},
        )

    recorder_calls = {"n": 0}

    def counting_handler(request: httpx.Request) -> httpx.Response:
        recorder_calls["n"] += 1
        return handler(request)

    slept: list[float] = []

    async def sleeper(seconds: float) -> None:
        slept.append(seconds)

    provider = OpenAICompatibleProvider(
        CONFIG,
        transport=httpx.MockTransport(counting_handler),
        retry=RetryPolicy(attempts=4, base_delay_ms=10),
        sleeper=sleeper,
    )

    with pytest.raises(ProviderQuotaExhaustedError) as info:
        await _ask(provider)

    assert recorder_calls["n"] == 1, "额度用尽不该重试：等的是次日重置，不是一个退避周期"
    assert slept == []
    assert "额度已用尽" in str(info.value)
    assert "50" in str(info.value), "把每日上限说出来，才知道是不是该换模型"
    assert "重置" in str(info.value) and "重试无用" in str(info.value)
    assert info.value.retryable is False
    await provider.aclose()


async def test_transient_rate_limit_is_still_retried() -> None:
    """对比组：**剩余额度不为 0** 的 429 仍是瞬时限流，照常退避重试。"""

    def handler(request: httpx.Request) -> httpx.Response:
        del request
        return httpx.Response(
            429,
            headers={"x-ratelimit-limit": "20", "x-ratelimit-remaining": "3"},
            json={"error": {"message": "slow down"}},
        )

    attempts = {"n": 0}

    def counting_handler(request: httpx.Request) -> httpx.Response:
        attempts["n"] += 1
        return handler(request)

    async def sleeper(seconds: float) -> None:
        del seconds

    provider = OpenAICompatibleProvider(
        CONFIG,
        transport=httpx.MockTransport(counting_handler),
        retry=RetryPolicy(attempts=3, base_delay_ms=1),
        sleeper=sleeper,
    )

    with pytest.raises(ProviderRateLimitError) as info:
        await _ask(provider)

    assert attempts["n"] == 3
    assert not isinstance(info.value, ProviderQuotaExhaustedError)
    await provider.aclose()


async def test_retry_after_header_is_honoured_but_capped() -> None:
    """上游说了等多久就等多久（仍受策略上限封顶）。"""

    def handler(request: httpx.Request) -> httpx.Response:
        del request
        return httpx.Response(
            429,
            headers={"retry-after": "600", "x-ratelimit-remaining": "5"},
            json={"error": {"message": "slow down"}},
        )

    slept: list[float] = []

    async def sleeper(seconds: float) -> None:
        slept.append(seconds)

    provider = OpenAICompatibleProvider(
        CONFIG,
        transport=httpx.MockTransport(handler),
        retry=RetryPolicy(attempts=2, base_delay_ms=800, max_delay_ms=5_000),
        sleeper=sleeper,
    )

    with pytest.raises(ProviderRateLimitError):
        await _ask(provider)

    assert slept == [5.0], "Retry-After 说 600 秒，但不能真的在一个请求里等 10 分钟"
    await provider.aclose()


async def test_timeout_is_retried() -> None:
    """连接超时也算「值得重试」：链路抖动一次不该直接失败。"""
    attempts = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        del request
        attempts["n"] += 1
        if attempts["n"] == 1:
            raise httpx.ConnectTimeout("连接超时")
        return httpx.Response(200, json=OK_BODY)

    async def sleeper(seconds: float) -> None:
        del seconds

    provider = OpenAICompatibleProvider(
        CONFIG,
        transport=httpx.MockTransport(handler),
        retry=RetryPolicy(attempts=2, base_delay_ms=1),
        sleeper=sleeper,
    )

    await _ask(provider)

    assert attempts["n"] == 2
    await provider.aclose()
