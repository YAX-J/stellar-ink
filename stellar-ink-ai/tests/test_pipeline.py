"""检索管道的开关接线测试。

为什么值得单独测：管道是「评测台数字」与「线上链路」之间唯一的公共路径。
一旦某个开关接错（比如单路 Sparse 偷偷花了一次嵌入、重排的下标解释错位），
评测出来的对比表就会指向错误的结论 —— 而这类错误**不会报错**，只会让排序悄悄变样。
所以这里用受控向量与计数桩，把每个开关的行为钉死。
"""

from __future__ import annotations

from dataclasses import dataclass

import pytest

from app.providers.errors import ProviderError
from app.providers.models import (
    EmbeddingResponse,
    RerankResponse,
    RerankResult,
    TokenUsage,
)
from app.rag.pipeline import (
    IndexedChunk,
    RetrievalConfig,
    RetrievalPipeline,
    build_corpus,
)


@dataclass(frozen=True)
class _Post:
    post_id: int
    title: str
    plain: str


A = _Post(post_id=1, title="甲", plain="苹果苹果")
B = _Post(post_id=2, title="乙", plain="苹果")
C = _Post(post_id=3, title="丙", plain="与苹果无关的一段字")


class _Embedder:
    """受控嵌入桩：向量由调用方给定，并记录每次调用的文本（用于断言「有没有白花一次钱」）。"""

    def __init__(self, vector_for, dimension: int = 2) -> None:
        self._vector_for = vector_for
        self._dimension = dimension
        self.calls: list[list[str]] = []
        self.fail_count: int | None = None

    @property
    def queried(self) -> list[str]:
        return [text for batch in self.calls for text in batch]

    async def embed(self, texts: list[str]) -> EmbeddingResponse:
        self.calls.append(list(texts))
        vectors = [self._vector_for(text) for text in texts]
        if self.fail_count is not None:
            vectors = vectors[: self.fail_count]
        return EmbeddingResponse(vectors=vectors, dimension=self._dimension, usage=TokenUsage())


class _Reranker:
    """受控重排桩：记录收到的候选（顺序即下标语义），按给定顺序返回。"""

    def __init__(self, order: list[int] | None = None) -> None:
        self._order = order
        self.seen: list[list[str]] = []

    async def rerank(self, query, documents, *, top_n=None):
        self.seen.append(list(documents))
        order = self._order if self._order is not None else list(range(len(documents)))[::-1]
        results = [
            RerankResult(index=index, score=1.0 - position * 0.1)
            for position, index in enumerate(order)
        ]
        limit = top_n if top_n is not None else len(results)
        return RerankResponse(results=results[:limit], usage=TokenUsage())


def _vector_for(text: str) -> list[float]:
    """甲 → x 轴（Sparse 更爱它）；查询与丙 → y 轴（Dense 更爱乙/丙）。"""
    if text.startswith("甲"):
        return [1.0, 0.0]
    return [0.0, 1.0]


def _pipeline(
    config: RetrievalConfig, *, embedder=None, reranker=None, dense_store=None, corpus=None
) -> RetrievalPipeline:
    return RetrievalPipeline(
        corpus=corpus if corpus is not None else build_corpus([A, B, C]),
        config=config,
        embedder=embedder,
        reranker=reranker,
        dense_store=dense_store,
    )


@dataclass(frozen=True)
class _Hit:
    chunk_id: str
    post_id: int
    score: float


class _VectorStore:
    """假向量库：实现管道需要的 search 与指纹校验，并记录调用参数。"""

    def __init__(self, hits: list[_Hit], *, fingerprint_error: Exception | None = None) -> None:
        self._hits = hits
        self._fingerprint_error = fingerprint_error
        self.calls: list[tuple[list[float], int, float | None]] = []
        self.fingerprint_checks = 0

    async def search(self, vector, *, top_k, score_threshold=None):
        self.calls.append((list(vector), top_k, score_threshold))
        return list(self._hits)

    async def assert_model_fingerprint(self, expected=None):
        self.fingerprint_checks += 1
        if self._fingerprint_error is not None:
            raise self._fingerprint_error
        return None


def test_corpus_keeps_child_chunks_with_title_prefix() -> None:
    corpus = build_corpus([A, B])

    assert [chunk.post_id for chunk in corpus] == [1, 2]
    assert corpus[0].text.startswith("甲\n"), "标题要进检索文本：它是「问的是哪一篇」的关键线索"
    assert corpus[0].chunk_id.endswith(":c0") or ":c" in corpus[0].chunk_id


def test_config_rejects_empty_and_negative_settings() -> None:
    with pytest.raises(ValueError, match="至少要启用一路召回"):
        RetrievalConfig(enable_sparse=False, enable_dense=False)
    with pytest.raises(ValueError, match="candidate_k"):
        RetrievalConfig(candidate_k=0)
    with pytest.raises(ValueError, match="不能为负"):
        RetrievalConfig(dense_weight=-1)
    with pytest.raises(ValueError, match="min_score_ratio"):
        RetrievalConfig(min_score_ratio=1.0)
    with pytest.raises(ValueError, match="min_dense_score"):
        RetrievalConfig(min_dense_score=1.5)


def test_enabled_paths_require_their_models() -> None:
    """缺模型必须当场报错：静默降级会让评测结果与线上不是同一件事。"""
    with pytest.raises(ValueError, match="embedder"):
        _pipeline(RetrievalConfig(enable_dense=True))
    with pytest.raises(ValueError, match="reranker"):
        _pipeline(RetrievalConfig(enable_rerank=True), embedder=_Embedder(_vector_for))


async def test_sparse_only_never_calls_the_embedder() -> None:
    embedder = _Embedder(_vector_for)
    pipeline = _pipeline(RetrievalConfig(enable_sparse=True, enable_dense=False), embedder=embedder)

    outcome = await pipeline.retrieve("苹果", top_k=3)

    assert embedder.calls == [], "单路 Sparse 不该产生嵌入调用（那是白花的钱与延迟）"
    assert pipeline.embed_calls == 0
    assert outcome.posts[0] == 1, "词频更高的甲应排第一"
    assert outcome.refused is False


async def test_dense_only_ranks_by_cosine() -> None:
    embedder = _Embedder(_vector_for)
    pipeline = _pipeline(RetrievalConfig(enable_sparse=False, enable_dense=True), embedder=embedder)

    outcome = await pipeline.retrieve("苹果", top_k=3)

    assert outcome.posts[0] == 2, "查询向量指向 y 轴，乙（与丙）应排在甲前面"
    assert pipeline.embed_calls == 2, "一次语料嵌入 + 一次查询嵌入"


async def test_prepare_is_idempotent() -> None:
    embedder = _Embedder(_vector_for)
    pipeline = _pipeline(RetrievalConfig(enable_sparse=False, enable_dense=True), embedder=embedder)

    await pipeline.prepare()
    await pipeline.prepare()
    await pipeline.retrieve("苹果", top_k=1)

    assert pipeline.embed_calls == 2, "重复 prepare 不该重新嵌入语料"


async def test_hybrid_weights_flip_the_order() -> None:
    """Sparse 偏爱甲、Dense 偏爱乙；改权重必须能把第一名换过来，否则开关是假的。"""
    sparse_heavy = _pipeline(
        RetrievalConfig(sparse_weight=10.0, dense_weight=1.0), embedder=_Embedder(_vector_for)
    )
    dense_heavy = _pipeline(
        RetrievalConfig(sparse_weight=1.0, dense_weight=10.0), embedder=_Embedder(_vector_for)
    )

    assert (await sparse_heavy.retrieve("苹果", top_k=3)).posts[0] == 1
    assert (await dense_heavy.retrieve("苹果", top_k=3)).posts[0] == 2


async def test_rerank_gets_candidates_in_order_and_drives_the_result() -> None:
    reranker = _Reranker(order=[1, 0])  # 把第二名提到第一
    pipeline = _pipeline(
        RetrievalConfig(enable_sparse=True, enable_dense=False, enable_rerank=True),
        reranker=reranker,
    )

    outcome = await pipeline.retrieve("苹果", top_k=2)

    sent = reranker.seen[0]
    assert sent[0].startswith("甲\n"), "送给重排的候选顺序必须与召回排名一致（下标即位置）"
    assert outcome.posts[0] == 2, "重排把乙提到第一后，结果必须跟着变"


async def test_rerank_out_of_range_index_fails_loudly() -> None:
    """下标越界必须报错：默默忽略就等于「重排没生效」，引用会指向没被选中的段落。"""
    pipeline = _pipeline(
        RetrievalConfig(enable_sparse=True, enable_dense=False, enable_rerank=True),
        reranker=_Reranker(order=[0, 99]),
    )

    with pytest.raises(ValueError, match="越界"):
        await pipeline.retrieve("苹果", top_k=2)


async def test_rerank_duplicate_index_fails_loudly() -> None:
    pipeline = _pipeline(
        RetrievalConfig(enable_sparse=True, enable_dense=False, enable_rerank=True),
        reranker=_Reranker(order=[0, 0]),
    )

    with pytest.raises(ValueError, match="重复下标"):
        await pipeline.retrieve("苹果", top_k=2)


async def test_embedding_count_mismatch_fails_loudly() -> None:
    embedder = _Embedder(_vector_for)
    embedder.fail_count = 2  # 3 个 chunk 只回 2 条向量
    pipeline = _pipeline(RetrievalConfig(enable_sparse=False, enable_dense=True), embedder=embedder)

    with pytest.raises(ValueError, match="嵌入数量与语料不一致"):
        await pipeline.prepare()


async def test_dense_floor_can_refuse() -> None:
    """余弦下限是 Dense 通路唯一的拒答机制：全被挡掉时必须如实拒答。"""
    # 查询向量与所有语料向量正交（余弦 = 0），因此任何正的下限都会把它们全部挡掉
    embedder = _Embedder(lambda text: [0.0, 1.0] if text == "苹果" else [1.0, 0.0])
    pipeline = _pipeline(
        RetrievalConfig(enable_sparse=False, enable_dense=True, min_dense_score=0.9),
        embedder=embedder,
    )

    outcome = await pipeline.retrieve("苹果", top_k=3)

    assert outcome.posts == []
    assert outcome.refused is True, "没有候选就要说没有依据，不能返回空列表装作待评估"


async def test_sparse_floor_can_refuse() -> None:
    pipeline = _pipeline(
        RetrievalConfig(enable_sparse=True, enable_dense=False, min_score=10_000.0)
    )

    outcome = await pipeline.retrieve("苹果", top_k=3)

    assert outcome.refused is True


async def test_posts_are_deduplicated_and_chunks_follow_accepted_posts() -> None:
    corpus = build_corpus([A, B, C])
    pipeline = RetrievalPipeline(
        corpus=corpus, config=RetrievalConfig(enable_sparse=True, enable_dense=False)
    )

    outcome = await pipeline.retrieve("苹果", top_k=1)

    assert len(outcome.posts) == 1
    accepted = {chunk.chunk_id: chunk.post_id for chunk in corpus}
    assert {accepted[chunk_id] for chunk_id in outcome.chunks} == {outcome.posts[0]}
    assert outcome.latency_ms >= 0


def test_describe_exposes_the_frontend_switches() -> None:
    described = RetrievalConfig(
        enable_sparse=True, enable_dense=False, enable_rerank=False, label=""
    ).describe()

    assert described["label"] == "sparse"
    assert described["paths"] == ["sparse"]
    assert described["rerank"] is False
    assert described["rrfK"] == 60
    assert RetrievalConfig(label="自定义").describe()["label"] == "自定义"


def test_indexed_chunk_is_plain_data() -> None:
    chunk = IndexedChunk(chunk_id="p1:c0", post_id=1, text="甲\n苹果")

    assert chunk.chunk_id == "p1:c0"


async def test_dense_store_drives_the_dense_path() -> None:
    """给了向量库就用它检索：本地不再需要预计算向量，省一次全量嵌入。"""
    corpus = build_corpus([A, B, C])
    store = _VectorStore(
        [
            _Hit(chunk_id=corpus[1].chunk_id, post_id=2, score=0.91),
            _Hit(chunk_id=corpus[0].chunk_id, post_id=1, score=0.42),
        ]
    )
    embedder = _Embedder(_vector_for)
    pipeline = _pipeline(
        RetrievalConfig(enable_sparse=False, enable_dense=True, min_dense_score=0.3),
        embedder=embedder,
        dense_store=store,
        corpus=corpus,
    )

    await pipeline.prepare()
    assert pipeline.embed_calls == 0, "走向量库时不该为整个语料算一遍向量"

    outcome = await pipeline.retrieve("苹果", top_k=3)

    vector, top_k, threshold = store.calls[0]
    assert top_k == 30, "候选数用 candidate_k"
    assert threshold == 0.3, "拒答阈值必须传下去，否则「本地能拒答、线上不能」"
    assert vector == _vector_for("苹果")
    assert outcome.posts == [2, 1], "顺序以向量库返回的分数为准"
    assert pipeline.embed_calls == 1, "只花一次查询嵌入"


async def test_dense_store_fingerprint_is_checked_once_before_search() -> None:
    """走向量库时先校验「库里的向量是哪个模型建的」。

    换嵌入模型之后两边不在同一向量空间：检索**不会报错**，只会返回错的东西 ——
    这类「链路全对、结果全错」只能靠这层校验发现。
    """
    corpus = build_corpus([A, B, C])
    store = _VectorStore([_Hit(chunk_id=corpus[0].chunk_id, post_id=1, score=0.5)])
    pipeline = _pipeline(
        RetrievalConfig(enable_sparse=False, enable_dense=True),
        embedder=_Embedder(_vector_for),
        dense_store=store,
        corpus=corpus,
    )

    await pipeline.retrieve("苹果", top_k=2)
    await pipeline.retrieve("苹果", top_k=2)

    assert store.fingerprint_checks == 1, "每个管道实例只校验一次，不必每问一句都去 scroll"


async def test_dense_store_fingerprint_mismatch_fails_loudly() -> None:
    """指纹不一致要当场报错，而不是拿错的结果回答用户。"""
    store = _VectorStore(
        [],
        fingerprint_error=ProviderError("索引里的向量是用另一个嵌入模型建的"),
    )
    pipeline = _pipeline(
        RetrievalConfig(enable_sparse=False, enable_dense=True),
        embedder=_Embedder(_vector_for),
        dense_store=store,
        corpus=build_corpus([A, B, C]),
    )

    with pytest.raises(ProviderError, match="另一个嵌入模型"):
        await pipeline.retrieve("苹果", top_k=2)


async def test_dense_store_without_threshold_passes_none() -> None:
    corpus = build_corpus([A, B, C])
    store = _VectorStore([_Hit(chunk_id=corpus[0].chunk_id, post_id=1, score=0.5)])
    pipeline = _pipeline(
        RetrievalConfig(enable_sparse=False, enable_dense=True),
        embedder=_Embedder(_vector_for),
        dense_store=store,
        corpus=corpus,
    )

    await pipeline.retrieve("苹果", top_k=2)

    assert store.calls[0][2] is None, "没设下限时要显式传 None，而不是 0（0 会过滤掉负相似度）"


async def test_dense_store_can_refuse() -> None:
    store = _VectorStore([])
    pipeline = _pipeline(
        RetrievalConfig(enable_sparse=False, enable_dense=True, min_dense_score=0.8),
        embedder=_Embedder(_vector_for),
        dense_store=store,
        corpus=build_corpus([A, B, C]),
    )

    outcome = await pipeline.retrieve("苹果", top_k=3)

    assert outcome.posts == []
    assert outcome.refused is True


async def test_unknown_chunk_from_store_fails_loudly() -> None:
    """索引与语料不是同一批时，命中回不到本地 chunk —— 必须报错，否则引用会指向别处。"""
    store = _VectorStore([_Hit(chunk_id="p99:v1:c0", post_id=99, score=0.9)])
    pipeline = _pipeline(
        RetrievalConfig(enable_sparse=False, enable_dense=True),
        embedder=_Embedder(_vector_for),
        dense_store=store,
    )

    with pytest.raises(ValueError, match="索引与当前语料不一致"):
        await pipeline.retrieve("苹果", top_k=3)


async def test_hybrid_with_store_fuses_both_paths() -> None:
    corpus = build_corpus([A, B, C])
    store = _VectorStore([_Hit(chunk_id=corpus[1].chunk_id, post_id=2, score=0.9)])
    pipeline = _pipeline(
        RetrievalConfig(enable_sparse=True, enable_dense=True),
        embedder=_Embedder(_vector_for),
        dense_store=store,
        corpus=corpus,
    )

    outcome = await pipeline.retrieve("苹果", top_k=3)

    assert 2 in outcome.posts, "向量库那一路的命中要在融合结果里"
    assert 1 in outcome.posts, "Sparse 偏爱的那篇也要在（融合不是取交集）"


def test_duplicate_chunk_ids_are_rejected() -> None:
    """重复 chunk_id 会让引用张冠李戴，而排序看起来完全正常 —— 必须在构造时拦下。"""
    duplicated = [
        IndexedChunk(chunk_id="p1:v1:c0", post_id=1, text="甲"),
        IndexedChunk(chunk_id="p1:v1:c0", post_id=2, text="乙"),
    ]

    with pytest.raises(ValueError, match="重复的 chunk_id"):
        RetrievalPipeline(corpus=duplicated, config=RetrievalConfig(enable_dense=False))


def test_corpus_carries_qdrant_payload() -> None:
    corpus = build_corpus([A])

    assert corpus[0].payload["chunkId"] == corpus[0].chunk_id
    assert corpus[0].payload["postId"] == 1
    assert corpus[0].payload["contentHash"], "写库的元数据要能定位到原文片段"
