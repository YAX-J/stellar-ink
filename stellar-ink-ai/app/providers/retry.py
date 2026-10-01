"""模型调用的重试策略：只重试**值得重试**的失败，且退避有上限。

为什么必须重试（实测）：`compare_strategies --provider panel` 跑标准五组时，
第 4 组触发免费档 429，30 道题**全部**被降级成「拒答」—— 一行 `recall 0` 看起来像
「开了重排就彻底失效」。一次限流不该毁掉整轮评测。

为什么不能无限重试：429 是**配额**语义，重试到天荒地老只会把并发全占住。
所以 attempts 有上限、退避有上限，用尽之后如实抛 `ProviderRateLimitError`（可读的那句仍在）。

判据直接复用错误分类里的 `retryable`（429 / 5xx / 超时 / 连不上为真；
401、403、400、能力不符为假）——重试规则只有一份，不会与错误分类悄悄分叉。
"""

from __future__ import annotations

from dataclasses import dataclass

from app.providers.errors import ProviderError


@dataclass(frozen=True, slots=True)
class RetryPolicy:
    """退避重试参数。默认值面向「免费档偶发 429」这个真实场景。"""

    #: 总尝试次数（含首次）。4 次 ≈ 首次 + 800ms + 1.6s + 3.2s
    attempts: int = 4
    #: 首次退避（毫秒），之后翻倍
    base_delay_ms: int = 800
    #: 单次退避上限（毫秒）：不设上限的话 attempts 一调大就会等到天荒地老
    max_delay_ms: int = 8_000

    #: 关掉重试（单测与「我要立刻知道上游挂了」的排障场景）
    @staticmethod
    def disabled() -> RetryPolicy:
        return RetryPolicy(attempts=1)

    def delay_for(self, attempt: int) -> float:
        """第 `attempt` 次失败之后要等多少秒（attempt 从 1 开始）。

        **确定性退避，不加随机抖动**：抖动能让多客户端错峰，但会让测试与排障无法预估
        「最多等多久」。当前量级（单人博客、单实例）不需要错峰。
        """
        if attempt < 1:
            raise ValueError("attempt 从 1 开始")
        # 用位移而不是 `2 ** n`：后者的类型推断会退化成 Any（mypy 的 __pow__ 重载）
        delay_ms: int = min(self.base_delay_ms * (1 << (attempt - 1)), self.max_delay_ms)
        return delay_ms / 1000

    def should_retry(self, error: ProviderError) -> bool:
        """按错误自己的 `retryable` 判定 —— 规则只有一份。"""
        return bool(error.retryable)
