"""确定性假实现：没有密钥也能跑通全部单测、评测流程与前端联调。

为什么它不是「测试专用代码」：评测台（C 阶段）需要**固定策略、去掉模型随机性**才能比较
检索质量；前端调参也需要一个不烧钱、不超时的后端。因此 Fake 是正式的一等实现，
通过配置 `provider: fake` 启用，而不是只在测试里 import。

设计要点：
- 输出只由输入决定（同一输入永远同一输出），便于断言与回归。
- 向量是「按文本哈希生成的正交性较好的伪向量」：语料不同则距离不同，
  足以驱动检索链路的排序与召回率计算，但不代表真实语义。
- 明确标注 `is_fake`，避免有人把它当成真实模型结果做结论。
"""

from __future__ import annotations

import hashlib
import math

from app.providers.errors import ProviderError
from app.providers.models import (
    ChatMessage,
    ChatResponse,
    EmbeddingResponse,
    ProviderCapabilities,
    ProviderConfig,
    RerankResponse,
    RerankResult,
    TokenUsage,
)


class FakeProvider:
    """确定性的 chat / embedding / rerank 假实现。"""

    #: 让调用方一眼看出结果不来自真实模型（会写进响应的 model 字段）
    MODEL_TAG = "fake"

    def __init__(self, config: ProviderConfig | None = None) -> None:
        self._config = config or ProviderConfig(
            role="fake",
            provider="fake",
            base_url="http://fake.local",
            model=self.MODEL_TAG,
            capabilities=ProviderCapabilities(chat=True, embedding=True, rerank=True),
        )
        self._dimension = self._config.dimension or 64

    @property
    def config(self) -> ProviderConfig:
        return self._config

    @property
    def capabilities(self) -> ProviderCapabilities:
        return self._config.capabilities

    async def chat(
        self,
        messages: list[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> ChatResponse:
        last_user = next(
            (message.content for message in reversed(messages) if message.role == "user"),
            "",
        )
        # 回显式答案：便于端到端断言「输入确实传到了这一步」，同时带上可识别的前缀
        text = f"[fake] 已收到 {len(messages)} 条消息，最后一条用户输入：{last_user.strip()[:60]}"
        prompt_tokens = sum(_approx_tokens(message.content) for message in messages)
        completion_tokens = _approx_tokens(text)
        return ChatResponse(
            text=text,
            finish_reason="stop",
            usage=TokenUsage.of(
                prompt_tokens,
                completion_tokens,
                latency_ms=0,
                model=self.MODEL_TAG,
            ),
        )

    async def embed(self, texts: list[str]) -> EmbeddingResponse:
        vectors = [self._pseudo_vector(text) for text in texts]
        return EmbeddingResponse(
            vectors=vectors,
            dimension=self._dimension,
            usage=TokenUsage.of(
                sum(_approx_tokens(text) for text in texts),
                0,
                latency_ms=0,
                model=self.MODEL_TAG,
            ),
        )

    async def rerank(
        self,
        query: str,
        documents: list[str],
        *,
        top_n: int | None = None,
    ) -> RerankResponse:
        # 与真实 Provider 同一条契约：空候选是调用方错误，不该悄悄返回空结果
        if not documents:
            raise ProviderError("重排候选不能为空")
        query_vector = self._pseudo_vector(query)
        scored = [
            RerankResult(index=index, score=_cosine(query_vector, self._pseudo_vector(document)))
            for index, document in enumerate(documents)
        ]
        scored.sort(key=lambda item: item.score, reverse=True)
        limit = top_n if top_n is not None else len(scored)
        return RerankResponse(
            results=scored[:limit],
            usage=TokenUsage.of(_approx_tokens(query), 0, latency_ms=0, model=self.MODEL_TAG),
        )

    def _pseudo_vector(self, text: str) -> list[float]:
        """按文本哈希铺开成固定维度向量：同文本必得同向量，不同文本大概率不同方向。"""
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        raw = [(byte / 255.0) - 0.5 for byte in digest]
        # 把 32 字节循环铺满目标维度，再做 L2 归一化（余弦距离才有意义）
        values = [raw[index % len(raw)] for index in range(self._dimension)]
        norm = math.sqrt(sum(value * value for value in values)) or 1.0
        return [round(value / norm, 6) for value in values]


def _approx_tokens(text: str) -> int:
    """粗略估算 Token：中文按字、英文按 4 字符。只用于让用量字段非零且稳定。"""
    if not text:
        return 0
    cjk = sum(1 for char in text if "\u4e00" <= char <= "\u9fff")
    others = len(text) - cjk
    return cjk + max(1, others // 4) if others else cjk


def _cosine(left: list[float], right: list[float]) -> float:
    dot = sum(a * b for a, b in zip(left, right, strict=False))
    return round(max(-1.0, min(1.0, dot)), 6)
