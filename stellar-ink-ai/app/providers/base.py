"""三类模型接口：聊天、嵌入、重排。

为什么拆成三个而不是一个「LLMClient」：三者的输入输出、失败形态与成本模型都不一样 ——
重排是「给定 query 与候选，返回分数」，嵌入是「文本 → 向量」，聊天是「消息 → 文本 + 用量」。
合成一个万能类的结果是每个方法里都要判断「这个 provider 到底支持哪种调用」，
不如让类型系统替我们判断（见 `ProviderCapabilities`）。

实现方约定：**不吞异常**。所有失败都抛 `app.providers.errors` 里的分类错误，
让上层能区分「可重试」与「必须改配置」。
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from app.providers.models import (
    ChatMessage,
    ChatResponse,
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
