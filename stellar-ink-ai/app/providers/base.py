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


def model_tag_of(model: object) -> str:
    """取一个可展示的模型标识（写进 `usage.model` / SSE 的 `meta.model`）。

    三档来源，按「越具体越优先」排：
    1. `MODEL_TAG`：离线桩自报家门（`fake` / `fake-copilot`），前端据此显示「离线自测」；
    2. `config.model`：真实 Provider 的模型名就在它的 `ProviderConfig` 上；
    3. `model` / `name`：第三方实现直接把自己当属性挂着。

    为什么要收成一个函数：问答编排原来只 `getattr("model")`，
    而真实 Provider 的模型名在 `config.model` 上 —— 于是接上真模型之后，
    流式响应的元信息里模型名一直是 `unknown`，链路完全正常却看起来像没接上。
    """
    tag = getattr(model, "MODEL_TAG", None)
    if isinstance(tag, str) and tag:
        return tag
    config_model = getattr(getattr(model, "config", None), "model", None)
    if isinstance(config_model, str) and config_model:
        return config_model
    for attribute in ("model", "name"):
        value = getattr(model, attribute, None)
        if isinstance(value, str) and value:
            return value
    return "unknown"
