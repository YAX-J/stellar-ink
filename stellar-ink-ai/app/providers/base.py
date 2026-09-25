"""三类模型接口：聊天、嵌入、重排。

为什么拆成三个而不是一个「LLMClient」：三者的输入输出、失败形态与成本模型都不一样 ——
重排是「给定 query 与候选，返回分数」，嵌入是「文本 → 向量」，聊天是「消息 → 文本 + 用量」。
合成一个万能类的结果是每个方法里都要判断「这个 provider 到底支持哪种调用」，
不如让类型系统替我们判断（见 `ProviderCapabilities`）。

实现方约定：**不吞异常**。所有失败都抛 `app.providers.errors` 里的分类错误，
让上层能区分「可重试」与「必须改配置」。
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Protocol, runtime_checkable

from app.providers.models import (
    ChatMessage,
    ChatResponse,
    ChatStreamChunk,
    EmbeddingResponse,
    RerankResponse,
)


@runtime_checkable
class ChatModel(Protocol):
    """文本生成。`messages` 里**不得包含**未授权的草稿内容（由上层保证）。"""

    async def chat(
        self,
        messages: list[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> ChatResponse: ...


@runtime_checkable
class StreamingChatModel(Protocol):
    """**可选**能力：支持增量输出的对话模型。

    刻意不并进 `ChatModel`：并不是每个 Provider 都能流式（部分自建推理服务只给一次性响应），
    硬塞进同一个协议会逼所有实现都写一个 `raise UnsupportedCapabilityError` 的桩，
    于是「支持流式」这件事在类型上就消失了。问答编排用 `isinstance(chat, StreamingChatModel)`
    判断，拿不到就走一次性回答 —— 用户体验差一点，但功能不会坏。
    """

    def stream_chat(
        self,
        messages: list[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[ChatStreamChunk]: ...


@runtime_checkable
class EmbeddingModel(Protocol):
    """文本 → 向量。返回的顺序必须与输入一一对应（检索靠下标对齐，错位会导致引用串位）。"""

    async def embed(self, texts: list[str]) -> EmbeddingResponse: ...


@runtime_checkable
class RerankModel(Protocol):
    """候选精排。只返回**下标与分数**，不回传原文，避免正文被上游改写后无法对齐。"""

    async def rerank(
        self,
        query: str,
        documents: list[str],
        *,
        top_n: int | None = None,
    ) -> RerankResponse: ...
