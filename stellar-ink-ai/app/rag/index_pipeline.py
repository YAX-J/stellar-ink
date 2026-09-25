"""索引管道：文章 → 切块 → 嵌入 → 写进 Qdrant，可重跑、可增量清理。

为什么单独成一层（而不是塞进检索管道）：它是**写路径**，只在「文章变了」时跑；
检索管道是**读路径**，每个请求都跑。两者的失败处理完全不同 ——
写路径失败要重试并把哪一批没写成功说清楚，读路径失败要降级或拒答。

顺序是刻意安排的（写操作不可逆，先把可能失败的都做在前面）：
1. 切块 → 2. 嵌入（可能因限流/超时失败）→ 3. 建集合（维度必须与嵌入一致）→
4. 删除这批文章与「已消失的文章」的旧点 → 5. 分批写入。

关于清理：Qdrant 没有「删除不在列表里的点」这种操作，所以按文章删是最实际的做法 ——
本次要重建的文章（`posts` 里出现的 post_id）先删掉再写，`removed_post_ids`（已删除/转私密的文章）
直接删掉，避免旧片段继续被检索到、引用指向已经不存在的文章。

用法（离线可验，不需要真 Qdrant）：

    points = await index_posts(store, posts, embedder)
"""

from __future__ import annotations

import time
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any, Protocol

from app.providers.base import EmbeddingModel
from app.providers.models import EmbeddingResponse
from app.rag.chunking import ChunkingConfig
from app.rag.pipeline import IndexedChunk, PostLike, build_corpus

#: 一批写多少个点：太大的一次请求容易触碰 body 上限与超时，太小则请求数暴涨
DEFAULT_BATCH_SIZE = 64


class IndexStore(Protocol):
    """索引写路径需要的向量库能力（Qdrant 只是其中一种实现）。"""

    async def ensure_collection(self, *, dimension: int, recreate: bool = False) -> Any: ...

    async def upsert(self, points: Sequence[Any]) -> int: ...

    async def delete_by_post_ids(self, post_ids: Sequence[int]) -> None: ...


class VectorPointFactory(Protocol):
    """把「切块 + 向量」变成向量库认的点（由具体存储实现提供）。"""

    def point(self, chunk: IndexedChunk, vector: list[float]) -> Any: ...


@dataclass(frozen=True, slots=True)
class IndexReport:
    """一次索引的结果：够用来判断「这次到底写了什么、有没有漏」。"""

    posts: int
    chunks: int
    written: int
    batches: int
    deleted_posts: int
    dimension: int
    embed_calls: int
    latency_ms: float
    skipped: str | None = None

    def to_row(self) -> dict[str, Any]:
        """可写进日志/审计/前端的形态（驼峰，与 Java 侧契约一致）。"""
        return {
            "posts": self.posts,
            "chunks": self.chunks,
            "written": self.written,
            "batches": self.batches,
            "deletedPosts": self.deleted_posts,
            "dimension": self.dimension,
            "embedCalls": self.embed_calls,
            "latencyMs": self.latency_ms,
            "skipped": self.skipped,
        }


@dataclass(slots=True)
class IndexPipeline:
    """写路径编排：切块 → 嵌入 → 建集合 → 清理旧点 → 分批写入。"""

    store: IndexStore
    embedder: EmbeddingModel
    point_factory: VectorPointFactory
    chunking: ChunkingConfig | None = None
    batch_size: int = DEFAULT_BATCH_SIZE
    _embed_calls: int = field(default=0, init=False)

    def __post_init__(self) -> None:
        if self.batch_size < 1:
            raise ValueError("batch_size 必须为正")

    @property
    def embed_calls(self) -> int:
        return self._embed_calls

    async def index(
        self,
        posts: list[PostLike],
        *,
        removed_post_ids: Sequence[int] = (),
        recreate: bool = False,
    ) -> IndexReport:
        started = time.perf_counter()
        removed = sorted({int(post_id) for post_id in removed_post_ids})
        corpus = build_corpus(posts, self.chunking)
        if not corpus:
            # 没有文章可索引时仍要清掉「已消失的文章」，否则删空的博客里还留着旧片段
            if removed:
                await self.store.delete_by_post_ids(removed)
            return IndexReport(
                posts=len(posts),
                chunks=0,
                written=0,
                batches=0,
                deleted_posts=len(removed),
                dimension=0,
                embed_calls=self._embed_calls,
                latency_ms=_elapsed_ms(started),
                skipped="没有可索引的子块",
            )

        vectors = await self._embed_all([chunk.text for chunk in corpus])
        dimension = len(vectors[0])
        await self.store.ensure_collection(dimension=dimension, recreate=recreate)

        # 本次重建的文章 + 已消失的文章：旧点先删，避免「改了文章但检索到旧片段」
        stale = sorted(set(removed) | {chunk.post_id for chunk in corpus})
        await self.store.delete_by_post_ids(stale)

        written = 0
        batches = 0
        for start in range(0, len(corpus), self.batch_size):
            window = slice(start, start + self.batch_size)
            points = [
                self.point_factory.point(chunk, vector)
                for chunk, vector in zip(corpus[window], vectors[window], strict=True)
            ]
            written += await self.store.upsert(points)
            batches += 1

        return IndexReport(
            posts=len(posts),
            chunks=len(corpus),
            written=written,
            batches=batches,
            deleted_posts=len(stale),
            dimension=dimension,
            embed_calls=self._embed_calls,
            latency_ms=_elapsed_ms(started),
        )

    async def _embed_all(self, texts: list[str]) -> list[list[float]]:
        """分批嵌入并**校验维度一致**：维度不齐的向量写进库里等于永久性错位。"""
        vectors: list[list[float]] = []
        for start in range(0, len(texts), self.batch_size):
            window = texts[start : start + self.batch_size]
            response = await self.embedder.embed(window)
            self._embed_calls += 1
            _validate_embedding(response, expected=len(window))
            vectors.extend(list(vector) for vector in response.vectors)
        return vectors


def _validate_embedding(response: EmbeddingResponse, *, expected: int) -> None:
    if len(response.vectors) != expected:
        raise ValueError(
            f"嵌入数量与输入不一致：{len(response.vectors)} != {expected}（会让 chunk 与向量错位）"
        )
    sizes = {len(vector) for vector in response.vectors}
    if len(sizes) > 1:
        raise ValueError(f"同一批嵌入里有多种维度 {sorted(sizes)}：无法写进同一个集合")
    if response.vectors and response.dimension != len(response.vectors[0]):
        raise ValueError(f"嵌入维度声明为 {response.dimension}，实际是 {len(response.vectors[0])}")


def _elapsed_ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000, 3)
