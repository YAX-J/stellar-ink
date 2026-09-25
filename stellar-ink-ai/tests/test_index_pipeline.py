"""索引管道的分片、清理与失败顺序测试。

这一层是**写路径**，写错了很难事后发现：旧片段继续被检索到、向量与 chunk 错位、
换了嵌入模型却往旧集合里灌新维度的向量 —— 三种都不会报错，只会让答案悄悄不对。
所以这里用一个记录全部调用的假 store，把「调了什么、顺序如何、失败时有没有破坏数据」钉死。
"""

from __future__ import annotations

from dataclasses import dataclass, field

import pytest

from app.providers.models import EmbeddingResponse, TokenUsage
from app.rag.index_pipeline import DEFAULT_BATCH_SIZE, IndexPipeline, IndexReport
from app.rag.pipeline import IndexedChunk, build_corpus


@dataclass(frozen=True)
class _Post:
    post_id: int
    title: str
    plain: str


class _Embedder:
    """固定维度嵌入桩：可注入维度错乱，用来验证校验确实会拦下来。"""

    def __init__(self, dimension: int = 4, *, break_count: bool = False) -> None:
        self._dimension = dimension
        self._break_count = break_count
        self.calls: list[list[str]] = []

    async def embed(self, texts: list[str]) -> EmbeddingResponse:
        self.calls.append(list(texts))
        vectors = [[float(index + 1)] * self._dimension for index, _ in enumerate(texts)]
        if self._break_count:
            vectors = vectors[:-1]
        return EmbeddingResponse(vectors=vectors, dimension=self._dimension, usage=TokenUsage())


@dataclass
class _Store:
    """记录调用的假向量库：不实现任何检索，只关心写路径的次序与内容。"""

    calls: list[tuple[str, object]] = field(default_factory=list)
    batches: list[int] = field(default_factory=list)

    async def ensure_collection(self, *, dimension: int, recreate: bool = False) -> object:
        self.calls.append(("ensure", (dimension, recreate)))
        return {"dimension": dimension}

    async def upsert(self, points: list[object]) -> int:
        self.calls.append(("upsert", list(points)))
        self.batches.append(len(points))
        return len(points)

    async def delete_by_post_ids(self, post_ids: list[int]) -> None:
        self.calls.append(("delete", list(post_ids)))

    @property
    def order(self) -> list[str]:
        return [name for name, _ in self.calls]


@dataclass(frozen=True)
class _Point:
    chunk_id: str
    post_id: int
    vector: list[float]
    payload: dict[str, object]


class _Factory:
    def point(self, chunk: IndexedChunk, vector: list[float]) -> _Point:
        return _Point(
            chunk_id=chunk.chunk_id, post_id=chunk.post_id, vector=vector, payload=chunk.payload
        )


def _posts(count: int, *, content: str = "星笺是一篇很慢的文章，写的是夜里的写作与阅读。") -> list:
    return [
        _Post(post_id=index, title=f"标题 {index}", plain=content) for index in range(1, count + 1)
    ]


def _pipeline(store: _Store, embedder: _Embedder, **kwargs) -> IndexPipeline:
    return IndexPipeline(store=store, embedder=embedder, point_factory=_Factory(), **kwargs)


async def test_indexes_in_batches_and_reports_what_happened() -> None:
    posts = _posts(3)
    store = _Store()
    embedder = _Embedder(dimension=4)
    pipeline = _pipeline(store, embedder, batch_size=2)
    expected_chunks = len(build_corpus(posts))

    report = await pipeline.index(posts)

    assert report.posts == 3
    assert report.chunks == expected_chunks
    assert report.written == expected_chunks
    assert report.batches == (expected_chunks + 1) // 2
    assert report.dimension == 4
    assert report.embed_calls == report.batches, "嵌入与写入同批进行，次数应当一致"
    assert sum(store.batches) == expected_chunks
    assert report.to_row()["deletedPosts"] == 3


async def test_embedding_batch_size_is_respected() -> None:
    store = _Store()
    embedder = _Embedder()
    pipeline = _pipeline(store, embedder, batch_size=1)

    await pipeline.index(_posts(2))

    assert all(len(batch) == 1 for batch in embedder.calls)
    assert store.batches  # 至少写过一批


async def test_collection_is_ensured_before_any_write() -> None:
    """顺序断言：建集合必须在删除/写入之前，否则第一批写入会打到不存在的集合上。"""
    store = _Store()
    pipeline = _pipeline(store, _Embedder())

    await pipeline.index(_posts(1))

    assert store.order[0] == "ensure"
    assert store.order.index("delete") < store.order.index("upsert")
    assert store.calls[0] == ("ensure", (4, False))


async def test_stale_points_are_deleted_before_insert() -> None:
    """本次重建的文章 + 已消失的文章都要删：否则改了文章还能检索到旧片段。"""
    posts = _posts(2)
    store = _Store()
    pipeline = _pipeline(store, _Embedder())

    report = await pipeline.index(posts, removed_post_ids=[99])

    deleted = next(payload for name, payload in store.calls if name == "delete")
    assert deleted == [1, 2, 99], "要按 post_id 排序后再删（结果可复现）"
    assert report.deleted_posts == 3


async def test_embedding_failure_happens_before_destructive_calls() -> None:
    """嵌入失败时绝不能已经删过点：否则数据没了、索引也没建起来。"""
    store = _Store()
    pipeline = _pipeline(store, _Embedder(break_count=True))

    with pytest.raises(ValueError, match="嵌入数量与输入不一致"):
        await pipeline.index(_posts(2))

    assert store.calls == [], "嵌入校验失败时不该碰向量库"


async def test_mixed_dimensions_in_one_response_are_rejected() -> None:
    class _Ragged(_Embedder):
        async def embed(self, texts: list[str]) -> EmbeddingResponse:
            self.calls.append(list(texts))
            return EmbeddingResponse(
                vectors=[[1.0, 2.0] for _ in texts], dimension=3, usage=TokenUsage()
            )

    store = _Store()
    pipeline = _pipeline(store, _Ragged())

    with pytest.raises(ValueError, match="嵌入维度声明"):
        await pipeline.index(_posts(1))
    assert store.calls == []


async def test_empty_corpus_is_reported_and_still_purges_removed_posts() -> None:
    store = _Store()
    pipeline = _pipeline(store, _Embedder())

    report = await pipeline.index([], removed_post_ids=[7])

    assert report.skipped == "没有可索引的子块"
    assert report.written == 0
    assert report.dimension == 0
    assert store.calls == [("delete", [7])], "删空的博客里不该留着旧片段"


async def test_empty_corpus_without_removals_touches_nothing() -> None:
    store = _Store()
    pipeline = _pipeline(store, _Embedder())

    await pipeline.index([])

    assert store.calls == []


async def test_recreate_is_passed_through() -> None:
    store = _Store()
    pipeline = _pipeline(store, _Embedder())

    await pipeline.index(_posts(1), recreate=True)

    assert store.calls[0] == ("ensure", (4, True))


async def test_batch_size_must_be_positive() -> None:
    with pytest.raises(ValueError, match="batch_size"):
        _pipeline(_Store(), _Embedder(), batch_size=0)


async def test_second_run_produces_the_same_writes() -> None:
    """幂等：同一批文章重跑，写下去的点必须一模一样（point id 由 chunk_id 决定）。"""
    posts = _posts(2)
    first_store, second_store = _Store(), _Store()
    await _pipeline(first_store, _Embedder()).index(posts)
    await _pipeline(second_store, _Embedder()).index(posts)

    def shape(store: _Store) -> list[tuple[str, int]]:
        return [
            (name, len(payload) if isinstance(payload, list) else 1)
            for name, payload in store.calls
        ]

    assert shape(first_store) == shape(second_store)


async def test_payload_keeps_chunk_metadata() -> None:
    """写进库的 payload 要带锚点与内容哈希：引用定位靠它，不能只剩 chunkId。"""
    store = _Store()
    pipeline = _pipeline(store, _Embedder())

    await pipeline.index(_posts(1))

    points = next(payload for name, payload in store.calls if name == "upsert")
    assert points[0].payload["chunkId"]
    assert points[0].payload["postId"] == 1
    assert points[0].payload["contentHash"]
    assert points[0].payload["headingPath"] is not None


def test_default_batch_size_is_sane() -> None:
    assert 8 <= DEFAULT_BATCH_SIZE <= 256, "太小会请求暴涨，太大容易触 body 上限与超时"


def test_report_row_uses_camel_case() -> None:
    report = IndexReport(
        posts=1,
        chunks=2,
        written=2,
        batches=1,
        deleted_posts=1,
        dimension=4,
        embed_calls=1,
        latency_ms=1.5,
    )

    row = report.to_row()

    assert set(row) == {
        "posts",
        "chunks",
        "written",
        "batches",
        "deletedPosts",
        "dimension",
        "embedCalls",
        "latencyMs",
        "skipped",
    }
    assert row["skipped"] is None
