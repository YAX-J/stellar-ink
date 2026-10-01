"""嵌入缓存：同一段文本在同一个模型上只嵌入一次。

为什么必须有它（实测）：评测一轮五组策略里有三组带 dense，而 `RetrievalPipeline.prepare()`
是**实例级**的 —— 三个管道各把整库（41 个子块）嵌入一遍。免费档下这一下就把额度打光，
于是第 4 组开始全线 429，30 道题全部降级成「拒答」，看起来像「开了重排就彻底失效」。
缓存之后整库只嵌入一次，逐题的查询嵌入命中率也高（同一批评测题会跨策略重复）。

三条刻意的口径：

1. **缓存键含模型指纹**：换嵌入模型后必须重新嵌入（维度与语义都变了）。
   只按文本做键的话，换模型会拿回旧向量 —— 那是最难查的一类错：链路全对，结果全是错的。
2. **有界**：LRU 上限（默认 4096 条向量）。语料是会长的，无上限的缓存就是内存泄漏。
3. **失败不缓存**：只有真的拿到向量才写入；上游 429 抛错时不留半条记录。

只包嵌入、不包 chat：对话是**有状态**的（每次问答的上下文都不同），缓存它没有意义；
而重排的输入是「一段查询 + 一组候选」，重复率也远低于嵌入。
"""

from __future__ import annotations

import hashlib
import logging
from collections import OrderedDict
from collections.abc import Sequence
from typing import Any

from app.providers.base import EmbeddingModel
from app.providers.models import EmbeddingResponse, ProviderConfig, TokenUsage

logger = logging.getLogger(__name__)

#: 缓存条数上限（一条 = 一段文本的向量）。41 个子块的语料绰绰有余，
#: 而即便配上千篇文章也不会无限涨。
DEFAULT_MAX_ENTRIES = 4096


class _VectorCache:
    """进程内的有界 LRU：键是 `(模型指纹, 文本哈希)`。

    进程内而不是 Redis：向量是**大量小对象**（2048 维 float），往返一次 Redis
    比重新算一次还慢；而且它只是「省一次调用」，丢了重新算即可，不需要跨实例共享。
    """

    def __init__(self, max_entries: int) -> None:
        if max_entries < 1:
            raise ValueError("嵌入缓存上限必须为正")
        self._items: OrderedDict[tuple[str, str], list[float]] = OrderedDict()
        self._max = max_entries
        self.hits = 0
        self.misses = 0

    def get(self, fingerprint: str, text: str) -> list[float] | None:
        key = (fingerprint, _digest(text))
        vector = self._items.get(key)
        if vector is None:
            self.misses += 1
            return None
        self._items.move_to_end(key)
        self.hits += 1
        return vector

    def put(self, fingerprint: str, text: str, vector: Sequence[float]) -> None:
        key = (fingerprint, _digest(text))
        self._items[key] = list(vector)
        self._items.move_to_end(key)
        while len(self._items) > self._max:
            self._items.popitem(last=False)

    def clear(self) -> None:
        self._items.clear()
        self.hits = 0
        self.misses = 0

    @property
    def size(self) -> int:
        return len(self._items)


#: 全局共享一份：同一模型的同一段文本，无论从哪个端点、哪条管道问，都只嵌入一次
_CACHE = _VectorCache(DEFAULT_MAX_ENTRIES)


def cache_stats() -> dict[str, int]:
    """给排障用：命中率能直接回答「整库到底嵌了几遍」。"""
    return {"entries": _CACHE.size, "hits": _CACHE.hits, "misses": _CACHE.misses}


def reset_embedding_cache() -> None:
    """清空缓存（测试与「换了语料想立刻重算」时用）。"""
    _CACHE.clear()


class CachingEmbeddingModel:
    """`EmbeddingModel` 的缓存包装：命中直接返回，未命中才打上游。

    ⚠️ 必须逐个文本对齐下标：只对**未命中**的那些发起一次批量调用，
    再按原顺序把结果拼回去。写错这里会让向量与文本错位 ——
    检索结果看起来正常，引用却指向另一段文字。
    """

    def __init__(self, inner: EmbeddingModel, *, cache: _VectorCache | None = None) -> None:
        self._inner = inner
        self._cache = cache if cache is not None else _CACHE

    @property
    def config(self) -> ProviderConfig | None:
        return getattr(self._inner, "config", None)

    @property
    def inner(self) -> EmbeddingModel:
        """给测试与排障用：拿回被包装的那个 provider。"""
        return self._inner

    async def embed(self, texts: list[str]) -> EmbeddingResponse:
        if not texts:
            raise ValueError("嵌入输入不能为空")
        fingerprint = self._fingerprint()
        resolved: list[list[float] | None] = [self._cache.get(fingerprint, text) for text in texts]
        missing = [index for index, vector in enumerate(resolved) if vector is None]

        usage = TokenUsage()
        if missing:
            response = await self._inner.embed([texts[index] for index in missing])
            if len(response.vectors) != len(missing):
                # 数量不齐会让下标错位：宁可失败也不要拼出错位的向量
                raise ValueError(
                    f"嵌入结果数量与请求不一致：{len(response.vectors)} != {len(missing)}"
                )
            for position, index in enumerate(missing):
                vector = list(response.vectors[position])
                resolved[index] = vector
                self._cache.put(fingerprint, texts[index], vector)
            usage = response.usage

        vectors = [vector for vector in resolved if vector is not None]
        if len(vectors) != len(texts):
            raise ValueError("嵌入结果数量与输入不一致（缓存与上游的合并出了问题）")
        dimension = len(vectors[0])
        logger.debug(
            "嵌入缓存：命中 %d / 请求 %d，命中率 %.2f",
            len(texts) - len(missing),
            len(texts),
            (len(texts) - len(missing)) / len(texts),
        )
        return EmbeddingResponse(vectors=vectors, dimension=dimension, usage=usage)

    def _fingerprint(self) -> str:
        config = self.config
        if config is None:
            # 拿不到配置（第三方实现）就退化成「按实例身份」：至少不会与别的模型混用
            return f"instance:{id(self._inner)}"
        return config.fingerprint()

    def __getattr__(self, name: str) -> Any:
        """其余能力（chat/rerank/aclose…）原样透传。

        为什么要有它：一个角色的配置可能同时声明多种能力（chat + embedding），
        包装之后那些方法还得能用 —— 漏掉的话表现是 `AttributeError`，
        与「能力不符」的报错完全是两回事，排查方向会被带偏。
        """
        return getattr(self._inner, name)


def _digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()
