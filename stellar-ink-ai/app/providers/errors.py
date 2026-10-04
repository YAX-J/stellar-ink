"""Provider 层的错误分类：让上层能按「可重试 / 该降级 / 配置错了」分别处理。

为什么不用一个笼统的 ProviderError 了事：这几类失败的处理方式完全不同 ——
限流与超时可以有限重试或换模型，鉴权与模型名错误重试一万次也没用（必须让人去改配置），
而不支持该能力则应该直接告诉调用方「这个角色没配这类模型」而不是当成上游故障。
"""

from __future__ import annotations

from typing import Any


class ProviderError(RuntimeError):
    """Provider 调用失败的基类。

    Attributes:
        code: 机器可读的错误码（写进对外错误体，前端可据此给不同文案）。
        retryable: 是否值得重试（限流、超时、5xx 为真；鉴权、模型名错误为假）。
    """

    code = "AI_PROVIDER_ERROR"
    retryable = False

    def __init__(self, message: str, *, detail: str | None = None) -> None:
        super().__init__(message)
        self.detail = detail

    def __str__(self) -> str:
        """把 `detail` 一起带出来。

        为什么重要：端点是拿 `str(error)` 去填响应的（`to_public_dict` 也是），
        而**可操作的提示往往写在 detail 里**（例如「请在 AI 实验室 → 模型配置里填写该角色」）。
        只返回 message 会让用户看到「角色 chat 尚未配置模型」却不知道去哪儿配 ——
        错误信息说清了「哪里不对」，但没说「该怎么办」，等于只说了一半。
        """
        if self.detail:
            return f"{super().__str__()}（{self.detail}）"
        return super().__str__()

    def to_public_dict(self) -> dict[str, Any]:
        """对外可见的最小信息：**不含密钥、不含内网地址、不含上游原始报文**。"""
        return {"code": self.code, "message": str(self), "retryable": self.retryable}


class ProviderAuthError(ProviderError):
    """密钥无效、过期或权限不足（401/403）。必须改配置，重试无意义。"""

    code = "AI_PROVIDER_AUTH"
    retryable = False


class ProviderRateLimitError(ProviderError):
    """被限流（429）：可以退避重试或路由到备用模型。"""

    code = "AI_RATE_LIMITED"
    retryable = True


class ProviderQuotaExhaustedError(ProviderRateLimitError):
    """**额度用尽**（例如免费档「50 次/日」）：退避重试毫无意义 —— 等的是次日重置。

    与父类的区别只在 `retryable=False`，但它带来两个实际后果：
    ① 重试策略不再白等（`should_retry` 直接读这个标志）；
    ② 消息里会说清「什么时候重置、现在该做什么」，而不是让人以为「过一分钟再试就行」。

    对外错误码**保持 `AI_RATE_LIMITED`**：前端已有 429 的专属文案
    （`isRateLimited()` 认 status/code 双 429）与 Java 的错误映射，不为了内部细分去改契约。
    """

    code = "AI_RATE_LIMITED"
    retryable = False


class ProviderTimeoutError(ProviderError):
    """连接或读取超时：链路抖动或上游过载，可重试。"""

    code = "AI_TIMEOUT"
    retryable = True


class ProviderUnavailableError(ProviderError):
    """上游不可用（5xx、连接失败、响应无法解析）：可重试，但连续失败应触发降级。"""

    code = "AI_UPSTREAM_UNAVAILABLE"
    retryable = True


class UnsupportedCapabilityError(ProviderError):
    """该 Provider 不具备这类能力（例如给 chat 角色配了 embedding 调用）。

    这属于**配置问题**：调用方应换用正确的角色，而不是重试。
    """

    code = "AI_PROVIDER_CAPABILITY"
    retryable = False


class InvalidBaseUrlError(ProviderError):
    """`base_url` 不过安全策略（用户级配置的 SSRF 闸门，见 `url_policy.py`）。

    单独一个类型是为了让 Java 侧能把它翻成**400 + 可操作提示**（「个人配置只能填公网地址」），
    而不是混进「服务不可用」那一堆里 —— 后者会让人去查网络，而问题在他填的那一栏。
    """

    retryable = False
