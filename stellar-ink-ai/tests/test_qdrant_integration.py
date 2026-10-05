"""端到端集成测试：索引管道 → 真实 Qdrant 客户端 → 检索管道，全部离线跑。

为什么要写这个：`test_qdrant_store.py` 只验证「请求长什么样」，`test_index_pipeline.py`
只验证「调用了谁」。真正容易错的是**两段拼起来**的地方 —— 写进去的 payload 键、
chunk_id 与本地语料的一致性、删除旧点后再检索是否还能命中。这些没有真 Qdrant 也能测：
用一个极小的内存 Qdrant 模拟器（`httpx.MockTransport` 上的余弦检索），
就能把「写路径 + 读路径」整条链路跑通。真机冒烟仍要跑（`scripts/qdrant_smoke.py`），
但那是为了验证协议细节，不是为了验证接线。
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from typing import Any

import httpx

from app.providers.fake import FakeProvider
from app.rag.index_pipeline import IndexPipeline
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline, build_corpus
from app.rag.qdrant_store import QdrantConfig, QdrantVectorStore


@dataclass(frozen=True)
class _Post:
    post_id: int
    title: str
    plain: str


def _cosine(left: list[float], right: list[float]) -> float:
    dot = sum(a * b for a, b in zip(left, right, strict=True))
    norm = (math.sqrt(sum(v * v for v in left)) or 1.0) * (
        math.sqrt(sum(v * v for v in right)) or 1.0
    )
    return dot / norm


@dataclass
class _FakeQdrant:
    """够用的内存 Qdrant：建/删集合、upsert、按 postId 删除、余弦检索。"""

    vector_size: int | None = None
    points: dict[int, dict[str, Any]] = field(default_factory=dict)
    requests: list[tuple[str, str]] = field(default_factory=list)

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self._handle)

    def _handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        self.requests.append((request.method, path))
        body = json.loads(request.content) if request.content else {}

        if path == "/":
            return httpx.Response(200, json={"title": "fake qdrant", "version": "1.12.4"})
        if path.startswith("/collections/"):
            rest = path[len("/collections/") :]
            name, _, tail = rest.partition("/")
            if request.method == "DELETE" and not tail:
                self.vector_size, self.points = None, {}
                return httpx.Response(200, json={"result": True})
            if request.method == "PUT" and not tail:
                self.vector_size = int(body["vectors"]["size"])
                return httpx.Response(200, json={"result": True})
            if request.method == "GET" and not tail:
                if self.vector_size is None:
                    return httpx.Response(404, json={"status": {"error": "not found"}})
                return httpx.Response(
                    200,
                    json={
                        "result": {
                            "status": "green",
                            "points_count": len(self.points),
                            "config": {
                                "params": {
                                    "vectors": {"size": self.vector_size, "distance": "Cosine"}
                                }
                            },
                        }
                    },
                )
            if tail == "points" and request.method == "PUT":
                for point in body["points"]:
                    self.points[int(point["id"])] = point
                return httpx.Response(200, json={"result": {"status": "completed"}})
            if tail == "points/delete":
                for key in [
                    key
                    for key, point in self.points.items()
                    if _matches(body["filter"], point["payload"])
                ]:
                    del self.points[key]
                return httpx.Response(200, json={"result": {"status": "completed"}})
            if tail == "points/search":
                threshold = body.get("score_threshold")
                scored = [
                    (
                        point,
                        _cosine(body["vector"], point["vector"]),
                    )
                    for point in self.points.values()
                ]
                scored.sort(key=lambda item: -item[1])
                rows = [
                    {"id": point["id"], "score": score, "payload": point["payload"]}
                    for point, score in scored
                    if threshold is None or score >= threshold
                ]
                return httpx.Response(200, json={"result": rows[: body["limit"]]})
        return httpx.Response(404, json={"status": {"error": f"unhandled {request.method} {path}"}})


def _matches(qfilter: dict[str, Any], payload: dict[str, Any]) -> bool:
    """按 Qdrant 的过滤语义判断一个点是否命中（够用的子集：should=OR、must=AND、match、is_empty）。

    ⚠️ **字段缺失不匹配任何 `match`** —— 这是 Qdrant 的真实行为，也正是
    「按 kind 过滤必须先写 payload、后过滤」那条教训的来源。fake 必须照它实现：
    若把「缺失」当成匹配，删除逻辑里 `is_empty` 那一支就永远测不到，
    而那支一旦缺失，升级后第一次重建会一条旧点都删不掉。
    """
    if "should" in qfilter:
        return any(_matches(clause, payload) for clause in qfilter["should"])
    if "must" in qfilter:
        return all(_matches(clause, payload) for clause in qfilter["must"])
    if "is_empty" in qfilter:
        return payload.get(qfilter["is_empty"]["key"]) in (None, "")
    key = qfilter.get("key")
    match = qfilter.get("match")
    if isinstance(key, str) and isinstance(match, dict):
        value = payload.get(key)
        if "any" in match:
            return value in match["any"]
        return value == match.get("value")
    return False


def _store(fake: _FakeQdrant) -> QdrantVectorStore:
    return QdrantVectorStore(
        QdrantConfig(collection="stellar_ink_chunks"), transport=fake.transport()
    )


def _posts() -> list[_Post]:
    return [
        _Post(
            post_id=1,
            title="在算法的洪流里做一个缓慢的人",
            plain="写得快不算活着，留下来的句子都在慢里熬出来。",
        ),
        _Post(
            post_id=2, title="一年写十八万字的方法", plain="不追求每天都写得好，只追求每天都写。"
        ),
        _Post(post_id=3, title="海边的路由器", plain="信号很差，睡眠很好。"),
    ]


async def test_index_then_retrieve_round_trip() -> None:
    fake = _FakeQdrant()
    store = _store(fake)
    provider = FakeProvider()
    posts = _posts()
    corpus = build_corpus(posts)

    report = await IndexPipeline(store=store, embedder=provider, point_factory=store).index(posts)

    assert report.written == len(corpus)
    assert fake.vector_size == 64, "集合维度必须等于嵌入模型维度"
    assert len(fake.points) == len(corpus)

    pipeline = RetrievalPipeline(
        corpus=corpus,
        config=RetrievalConfig(enable_sparse=True, enable_dense=True),
        embedder=provider,
        dense_store=store,
    )
    outcome = await pipeline.retrieve("我为什么坚持写博客", top_k=3)

    assert outcome.refused is False
    known = {chunk.chunk_id for chunk in corpus}
    assert set(outcome.chunks) <= known, "检索回来的 chunk 必须都能在本地语料里定位"
    await store.aclose()


async def test_reindexing_replaces_stale_chunks() -> None:
    """文章改短后再索引：旧片段必须被删掉，否则检索会命中已经不存在的内容。"""
    fake = _FakeQdrant()
    store = _store(fake)
    provider = FakeProvider()
    pipeline = IndexPipeline(store=store, embedder=provider, point_factory=store)
    paragraph = "这是一段足够长的正文内容，用来撑出多个子块。" * 8
    long_post = _Post(post_id=1, title="长文章", plain="\n\n".join([paragraph] * 4))
    await pipeline.index([long_post, _posts()[1]])
    before = {point["payload"]["chunkId"] for point in fake.points.values()}
    old_ids = {
        point["payload"]["chunkId"]
        for point in fake.points.values()
        if point["payload"]["postId"] == 1
    }
    assert len(old_ids) >= 2, "长文章应当切出多个子块，否则这个测试证明不了「删旧」"

    await pipeline.index([_Post(post_id=1, title="短文", plain="只剩一句话。"), _posts()[1]])

    remaining = {point["payload"]["chunkId"] for point in fake.points.values()}
    assert not (old_ids & remaining), "旧 chunk 必须被清掉"
    assert len(remaining) < len(before), "文章变短后总块数应当减少"
    assert {point["payload"]["postId"] for point in fake.points.values()} == {1, 2}
    await store.aclose()


async def test_removed_posts_are_purged_from_the_index() -> None:
    fake = _FakeQdrant()
    store = _store(fake)
    pipeline = IndexPipeline(store=store, embedder=FakeProvider(), point_factory=store)
    posts = _posts()
    await pipeline.index(posts)

    await pipeline.index([], removed_keys=[("post", 2)])

    assert {point["payload"]["postId"] for point in fake.points.values()} == {1, 3}
    await store.aclose()


async def test_dense_threshold_refuses_when_index_is_unrelated() -> None:
    """索引里只有别的主题时，余弦下限要能把候选全挡掉（拒答而非硬答）。"""
    fake = _FakeQdrant()
    store = _store(fake)
    provider = FakeProvider()
    posts = _posts()
    corpus = build_corpus(posts)
    await IndexPipeline(store=store, embedder=provider, point_factory=store).index(posts)

    pipeline = RetrievalPipeline(
        corpus=corpus,
        # 余弦上限就是 1.0：取满值即「只接受完全同向的向量」，用于验证阈值真的会清空候选
        config=RetrievalConfig(enable_sparse=False, enable_dense=True, min_dense_score=1.0),
        embedder=provider,
        dense_store=store,
    )
    outcome = await pipeline.retrieve("任意问题", top_k=3)

    assert outcome.refused is True, "阈值取满时，只有完全同向的向量才能过线"
    await store.aclose()


async def test_index_is_idempotent_on_rerun() -> None:
    fake = _FakeQdrant()
    store = _store(fake)
    pipeline = IndexPipeline(store=store, embedder=FakeProvider(), point_factory=store)

    await pipeline.index(_posts())
    first = set(fake.points)
    await pipeline.index(_posts())

    assert set(fake.points) == first, "point id 由 chunk_id 决定，重跑应覆盖同一批点"
    await store.aclose()
