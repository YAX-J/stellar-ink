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
