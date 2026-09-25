"""Provider 层的公共数据结构与确定性 ID。

这些模型是**内部协议**（Java ↔ Python 的对外契约在 `app/schemas`），
所以这里只关心「怎么把模型调用描述清楚」，不承担对外字段兼容的责任。
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any


class MessageRole(StrEnum):
    SYSTEM = "system"
    USER = "user"
    ASSISTANT = "assistant"


@dataclass(frozen=True, slots=True)
class ChatMessage:
    role: MessageRole
    content: str

    def to_payload(self) -> dict[str, str]:
        return {"role": self.role.value, "content": self.content}


@dataclass(frozen=True, slots=True)
class TokenUsage:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    latency_ms: int = 0
    model: str | None = None

    @staticmethod
    def of(
        prompt_tokens: int | None,
        completion_tokens: int | None,
        *,
        latency_ms: int,
        model: str | None,
    ) -> TokenUsage:
        prompt = prompt_tokens or 0
        completion = completion_tokens or 0
        # 上游偶尔只给总数或不给：这里不猜价格，只保证 total 至少等于两部分之和
        return TokenUsage(
            prompt_tokens=prompt,
            completion_tokens=completion,
            total_tokens=prompt + completion,
            latency_ms=latency_ms,
            model=model,
        )


@dataclass(frozen=True, slots=True)
class ChatResponse:
    text: str
    usage: TokenUsage
    finish_reason: str = "stop"

    @property
    def refused(self) -> bool:
        """上游是否自称「拒答」（部分模型会返回 refusal 字段或空内容 + 特定原因）。"""
        return self.finish_reason == "content_filter" or not self.text.strip()


@dataclass(frozen=True, slots=True)
class ChatStreamChunk:
    """流式对话的一个增量块。

    `text` 是**增量**（不是累积），调用方自己拼；`finish_reason` 只在最后一块上有值。
    用量也只在最后一块：多数 OpenAI 兼容服务要 `stream_options.include_usage` 才回，
    拿不到就留零 —— 宁可少报 Token，也不要编一个数字。
    """

    text: str = ""
    finish_reason: str | None = None
    usage: TokenUsage | None = None


@dataclass(frozen=True, slots=True)
class EmbeddingResponse:
    vectors: list[list[float]]
    dimension: int
    usage: TokenUsage


@dataclass(frozen=True, slots=True)
class RerankResult:
    index: int
    score: float


@dataclass(frozen=True, slots=True)
class RerankResponse:
    results: list[RerankResult]
    usage: TokenUsage


@dataclass(frozen=True, slots=True)
class ProviderCapabilities:
    """一个 Provider 实际支持哪些调用。

    必须显式声明而不是假设：同为「OpenAI 兼容」，有的服务只有 chat、
    有的 embedding 维度不同、有的完全不提供 rerank。业务层据此给出明确错误。
    """

    chat: bool = False
    embedding: bool = False
    rerank: bool = False

    def supports(self, capability: str) -> bool:
        return bool(getattr(self, capability, False))

    def describe(self) -> str:
        enabled = [name for name in ("chat", "embedding", "rerank") if self.supports(name)]
        return "/".join(enabled) if enabled else "none"


@dataclass(frozen=True, slots=True)
class ProviderConfig:
    """一个角色的运行时配置（面板里填的那些参数）。"""

    role: str
    provider: str
    base_url: str
    model: str
    api_key: str = ""
    dimension: int | None = None
    timeout_ms: int = 30_000
    max_tokens: int | None = None
    temperature: float | None = None
    capabilities: ProviderCapabilities = field(default_factory=ProviderCapabilities)

    def fingerprint(self) -> str:
        """配置指纹：**只含非敏感字段**，用于日志、缓存键与「配置变了吗」的比对。

        刻意把 api_key 排除在外：它一旦进日志或缓存键就等于泄露。
        密钥轮换不影响指纹，这是有意的 —— 指纹标识的是「用哪个模型」。
        """
        raw = "|".join(
            [
                self.role,
                self.provider,
                self.base_url.rstrip("/"),
                self.model,
                str(self.dimension or ""),
                str(self.timeout_ms),
                str(self.max_tokens or ""),
                str(self.temperature if self.temperature is not None else ""),
            ]
        )
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]

    def safe_summary(self) -> dict[str, Any]:
        """可安全写进日志/审计/前端提示的摘要。"""
        return {
            "role": self.role,
            "provider": self.provider,
            "model": self.model,
            "dimension": self.dimension,
            "timeoutMs": self.timeout_ms,
            "capabilities": self.capabilities.describe(),
            "fingerprint": self.fingerprint(),
        }
