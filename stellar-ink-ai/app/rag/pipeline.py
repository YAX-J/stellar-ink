"""检索管道：把「切块 → Sparse / Dense → RRF → Rerank → 拒答」编成一个可配置对象。

为什么要有这一层（而不是让评测台自己拼）：
- 评测台要对比的是**同一段编排、不同开关**。如果每种配置各写一段代码，
  「混合 + 重排」的评测结果与真实业务链路会慢慢分叉，数字就不再代表线上；
- 前端实验室要暴露的正是 `RetrievalConfig` 里那几个开关（`describe()` 就是那份快照），
  参数校验集中在这里，避免出现「两路都关」这种空转配置；
- 顺序固定：召回 → 融合（RRF）→ 精排（只对候选）→ post 级去重 → 空即拒答。
  chunk 级信息一路保留，引用才能定位到具体段落。

关于拒答（沿用实测结论，见 `docs/ai/fast-track-plan.md`）：
BM25 的绝对下限挡不住语义相近但无答案的问题（两个分数分布重叠），
所以这里另留 `min_dense_score`（余弦下限）——它是 Dense 通路唯一能让候选清空、
从而触发拒答的机制。两者都是「宁可少编造」的旋钮，默认不启用（0 = 不过滤）。
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Sequence
from dataclasses import dataclass, field
from typing import Protocol

from app.core.trace import record_event
from app.providers.base import EmbeddingModel, RerankModel
from app.rag.chunking import ChunkingConfig, PostDocument, chunk_document
from app.rag.eval_runner import RetrievalOutcome, RetrievedHit
from app.rag.retrieval import (
    DEFAULT_RRF_K,
    Bm25Index,
    ScoredChunk,
    dense_scores,
    reciprocal_rank_fusion,
)


class PostLike(Protocol):
    """管道只要求「有 id / 标题 / 正文」，不依赖种子脚本的类型（避免 app 反向 import scripts）。"""

    @property
    def post_id(self) -> int: ...

    @property
    def title(self) -> str: ...

    @property
    def plain(self) -> str: ...


@dataclass(frozen=True, slots=True)
class IndexedChunk:
    """一个进入索引的子块。

    `text` 是**检索用文本**（标题 + 正文片段）：用户问「那篇讲慢的文章」时，
    标题往往比正文更能确定「问的是哪一篇」。原文片段由 chunk_id 回查，不在这里复制。

    `payload` 是写进向量库的那份元数据（锚点、章节路径、内容哈希……），
    由 `Chunk.to_payload()` 直接给出：写库与引用定位用的是同一份字段，不会各写一套。
    """

    chunk_id: str
    post_id: int
    text: str
    payload: dict[str, object] = field(default_factory=dict)
    #: 文章标题：引用与提示词都要它（`text` 里虽然有，但那是给检索用的整段，拆出来更稳）
    title: str = ""


def build_corpus(
    posts: Sequence[PostLike], config: ChunkingConfig | None = None
) -> list[IndexedChunk]:
    """把文章切成检索单元：只保留子块（父块是喂上下文的，不参与召回）。"""
    chunks: list[IndexedChunk] = []
    for post in posts:
        document = PostDocument(post_id=post.post_id, title=post.title, content=post.plain)
        for chunk in chunk_document(document, config):
            if chunk.chunk_type != "child":
                continue
            chunks.append(
                IndexedChunk(
                    chunk_id=chunk.chunk_id,
                    post_id=post.post_id,
                    text=f"{post.title}\n{chunk.text}",
                    payload=chunk.to_payload(),
                    title=post.title,
                )
            )
    return chunks


class VectorHitLike(Protocol):
    """向量库命中的最小形状（只要够定位引用与排序）。"""

    @property
    def chunk_id(self) -> str: ...

    @property
    def post_id(self) -> int: ...

    @property
    def score(self) -> float: ...


class VectorStore(Protocol):
    """Dense 通路的后端：本层的 Dense 只认这个协议，Qdrant 只是其中一种实现。

    这样「本地余弦」与「向量库检索」可以并存对照（评测台要比较它们），
    也让管道不必 import 任何向量库 SDK。
    """

    async def search(
        self,
        vector: Sequence[float],
        *,
        top_k: int,
        score_threshold: float | None = None,
    ) -> Sequence[VectorHitLike]: ...


@dataclass(frozen=True, slots=True)
class RetrievalConfig:
    """一整套可切换的检索配置 —— 前端实验室的开关就是这些字段。"""

    enable_sparse: bool = True
    enable_dense: bool = True
    enable_rerank: bool = False
    #: 每路 recall 的候选数（融合与重排都在这个池子里做）
    candidate_k: int = 30
    sparse_weight: float = 1.0
    dense_weight: float = 1.0
    rrf_k: int = DEFAULT_RRF_K
    #: BM25 绝对下限（0 = 不过滤）；低于它的候选被丢弃，全丢光即拒答
    min_score: float = 0.0
    min_score_ratio: float = 0.0
    #: 余弦下限（0 = 不过滤）。语义相近但无答案的问题只能靠它挡
    min_dense_score: float = 0.0
    #: 重排后保留多少候选
    rerank_top_n: int = 10
    label: str = ""

    def __post_init__(self) -> None:
        if not self.enable_sparse and not self.enable_dense:
            raise ValueError("至少要启用一路召回，否则没有任何候选")
        if self.candidate_k < 1:
            raise ValueError("candidate_k 必须为正")
        if self.rrf_k < 1:
            raise ValueError("rrf_k 必须为正")
        if self.rerank_top_n < 1:
            raise ValueError("rerank_top_n 必须为正")
        for name in ("sparse_weight", "dense_weight"):
            if getattr(self, name) < 0:
                raise ValueError(f"{name} 不能为负：负权重会把相关结果往下压")
        if self.min_score < 0:
            raise ValueError("min_score 不能为负")
        if not 0 <= self.min_score_ratio < 1:
            raise ValueError("min_score_ratio 必须在 [0, 1)：等于 1 会过滤掉所有候选")
        if not -1 <= self.min_dense_score <= 1:
            raise ValueError("min_dense_score 是余弦下限，必须落在 [-1, 1]")

    def describe(self) -> dict[str, object]:
        """开关快照：写进 `ai_eval_run` 的策略描述、以及前端对照表的分组标签。"""
        paths = [
            name
            for name, on in (("sparse", self.enable_sparse), ("dense", self.enable_dense))
            if on
        ]
        return {
            "label": self.label or "+".join(paths) + ("+rerank" if self.enable_rerank else ""),
            "paths": paths,
            "rerank": self.enable_rerank,
            "candidateK": self.candidate_k,
            "weights": {"sparse": self.sparse_weight, "dense": self.dense_weight},
            "rrfK": self.rrf_k,
            "minScore": self.min_score,
            "minDenseScore": self.min_dense_score,
        }


@dataclass(slots=True)
class RetrievalPipeline:
    """可配置检索管道：实现评测运行器的 `Retriever` 协议。

    `embedder` / `reranker` 是注入进来的模型接口（Fake 与真实 Provider 同协议），
    这样「换模型不用改管道」，也保证评测台跑的就是业务链路。
    """

    corpus: list[IndexedChunk]
    config: RetrievalConfig = field(default_factory=RetrievalConfig)
    embedder: EmbeddingModel | None = None
    reranker: RerankModel | None = None
    #: 给了向量库就走向量库检索（真实链路），否则用本地余弦（离线评测与对照）
    dense_store: VectorStore | None = None
    #: 并发闸门：`None` = 不限制（单测与离线脚本用）
    max_concurrency: int | None = None
    _gate: asyncio.Semaphore | None = field(default=None, init=False)
    _queue_times: list[float] = field(default_factory=list, init=False)
    _sparse: Bm25Index | None = field(default=None, init=False)
    _vectors: list[list[float]] | None = field(default=None, init=False)
    _positions: dict[str, int] = field(default_factory=dict, init=False)
    _embed_calls: int = field(default=0, init=False)

    def __post_init__(self) -> None:
        if self.max_concurrency is not None and self.max_concurrency < 1:
            raise ValueError("max_concurrency 必须为正（None 表示不限制）")
        if self.config.enable_dense and self.embedder is None:
            raise ValueError("启用 dense 通路必须注入 embedder（本层不自己找模型）")
        if self.config.enable_rerank and self.reranker is None:
            raise ValueError("启用 rerank 必须注入 reranker（不允许静默降级成不重排）")
        self._positions = {chunk.chunk_id: index for index, chunk in enumerate(self.corpus)}
        if len(self._positions) != len(self.corpus):
            # 同一 chunk_id 出现两次会让引用指向另一段文字，而排序看起来一切正常
            raise ValueError("语料里存在重复的 chunk_id：引用定位会张冠李戴")
        if self.max_concurrency is not None:
            # 闸门在事件循环里创建：`RetrievalPipeline` 也会在同步上下文（脚本）里被构造，
            # 那里没有运行中的 loop，构造 Semaphore 会告警甚至绑定到错误的循环
            self._gate = asyncio.Semaphore(self.max_concurrency)

    @property
    def queue_wait_ms(self) -> int:
        """累计排队等待毫秒数：并发受限时「慢」有多少是等出来的，这个数字能回答。"""
        return int(sum(self._queue_times) * 1000)

    @property
    def embed_calls(self) -> int:
        """嵌入调用次数：测试用它证明「单路 Sparse 不会白花一次嵌入的钱」。"""
        return self._embed_calls

    async def prepare(self) -> None:
        """建索引并预计算向量。幂等：重复调用不会重复嵌入。

        走向量库（`dense_store`）时**不需要**本地向量：嵌入交给向量库那一侧（索引时已算好），
        这样检索期只花一次「查询嵌入」的钱，也不必把整库向量塞进内存。
        """
        if self._sparse is None:
            self._sparse = Bm25Index(
                min_score=self.config.min_score,
                min_score_ratio=self.config.min_score_ratio,
            ).fit([chunk.text for chunk in self.corpus])
        if self.config.enable_dense and self.dense_store is None and self._vectors is None:
            response = await _embedder_of(self).embed([chunk.text for chunk in self.corpus])
            self._embed_calls += 1
            if len(response.vectors) != len(self.corpus):
                # 数量不齐会让下标与 chunk 错位，进而让引用串到别的文章上
                raise ValueError(
                    f"嵌入数量与语料不一致：{len(response.vectors)} != {len(self.corpus)}"
                )
            for position, vector in enumerate(response.vectors):
                if len(vector) != response.dimension:
                    raise ValueError(
                        f"第 {position} 条向量维度 {len(vector)} 与声明的 {response.dimension} 不符"
                    )
            self._vectors = [list(vector) for vector in response.vectors]

    async def retrieve(self, question: str, *, top_k: int) -> RetrievalOutcome:
        if top_k < 1:
            raise ValueError("top_k 必须为正")
        await self.prepare()
        started = time.perf_counter()

        candidates = await self._gated(self._recall(question))
        if not candidates:
            # 没有候选就是「没有依据」，如实拒答；不得返回空列表装作「待评估」
            record_event(
                "retrieval",
                topK=top_k,
                candidates=0,
                posts=0,
                refused=True,
                latencyMs=_elapsed_ms(started),
            )
            return RetrievalOutcome(
                posts=[], chunks=[], refused=True, latency_ms=_elapsed_ms(started)
            )

        ranked = await self._rerank(question, candidates)
        accepted_posts: list[int] = []
        for candidate in ranked:
            post_id = self.corpus[candidate.chunk_index].post_id
            if post_id not in accepted_posts:
                accepted_posts.append(post_id)
            if len(accepted_posts) >= top_k:
                break

        # 引用只算「真正送进上下文」的块：属于被接受文章的那些，顺序与排名一致
        cited = [
            candidate
            for candidate in ranked
            if self.corpus[candidate.chunk_index].post_id in accepted_posts
        ]
        chunks = [self.corpus[candidate.chunk_index].chunk_id for candidate in cited]
        hits = [
            RetrievedHit(
                chunk_id=self.corpus[candidate.chunk_index].chunk_id,
                post_id=self.corpus[candidate.chunk_index].post_id,
                score=float(candidate.score),
                text=self.corpus[candidate.chunk_index].text,
            )
            for candidate in cited
        ]
        record_event(
            "retrieval",
            topK=top_k,
            candidates=len(candidates),
            posts=len(accepted_posts),
            chunks=len(chunks),
            refused=not accepted_posts,
            latencyMs=_elapsed_ms(started),
            # 开关也记下来：排障时「这次为什么没走向量」几乎总是配置问题
            sparse=self.config.enable_sparse,
            dense=self.config.enable_dense,
            rerank=self.config.enable_rerank,
        )
        return RetrievalOutcome(
            posts=accepted_posts,
            chunks=chunks,
            refused=False,
            latency_ms=_elapsed_ms(started),
            hits=hits,
        )

    async def _gated(self, work: Awaitable[list[ScoredChunk]]) -> list[ScoredChunk]:
        """并发闸门：只包住**召回**这一段。

        为什么只包召回：它是唯一会打外部服务（嵌入 / 向量库）的环节，也是配额与延迟的瓶颈。
        融合与重排是纯本地计算，限它们只会白白拉长尾延迟。

        排队时间单独记账（`queue_wait_ms`）：并发受限时「这次怎么这么慢」有两种完全不同的
        原因 —— 上游慢，还是自己在排队。混在一起看延迟百分位永远分不清。
        """
        if self._gate is None:
            return await work
        started = time.perf_counter()
        async with self._gate:
            self._queue_times.append(time.perf_counter() - started)
            return await work

    async def _recall(self, question: str) -> list[ScoredChunk]:
        """多路召回：每路各自 top_k，单路时不做融合（保留原始分数便于对照）。"""
        rankings: list[list[ScoredChunk]] = []
        weights: list[float] = []

        if self.config.enable_sparse:
            if self._sparse is None:  # pragma: no cover - prepare() 已建好
                raise RuntimeError("Sparse 索引尚未构建：retrieve 内部应先调用 prepare()")
            rankings.append(self._sparse.search(question, top_k=self.config.candidate_k))
            weights.append(self.config.sparse_weight)

        if self.config.enable_dense:
            response = await _embedder_of(self).embed([question])
            self._embed_calls += 1
            if len(response.vectors) != 1:
                raise ValueError(f"查询嵌入应返回 1 条向量，实际 {len(response.vectors)} 条")
            query_vector = response.vectors[0]
            rankings.append(await self._dense_recall(query_vector))
            weights.append(self.config.dense_weight)

        if len(rankings) == 1:
            return rankings[0]
        return reciprocal_rank_fusion(
            rankings, k=self.config.rrf_k, weights=weights, top_k=self.config.candidate_k
        )

    async def _dense_recall(self, query_vector: list[float]) -> list[ScoredChunk]:
        """Dense 召回：有向量库就走向量库，否则在内存里算余弦。

        两条路的差别只在「谁来算余弦」，因此拒答阈值（`min_dense_score`）都要传下去 ——
        否则「本地能拒答、线上不能」这种差异会一直藏到生产。
        """
        threshold = self.config.min_dense_score if self.config.min_dense_score > 0 else None
        if self.dense_store is not None:
            hits = await self.dense_store.search(
                query_vector, top_k=self.config.candidate_k, score_threshold=threshold
            )
            return [
                ScoredChunk(
                    chunk_index=self._position_of(hit.chunk_id),
                    score=float(hit.score),
                    source="dense",
                )
                for hit in hits
            ]

        if self._vectors is None:  # pragma: no cover - prepare() 已算好
            raise RuntimeError("向量尚未预计算：retrieve 内部应先调用 prepare()")
        dense_hits = dense_scores(query_vector, self._vectors, top_k=self.config.candidate_k)
        if self.config.min_dense_score > 0:
            dense_hits = [hit for hit in dense_hits if hit.score >= self.config.min_dense_score]
        return dense_hits

    def _position_of(self, chunk_id: str) -> int:
        """向量库命中要能回到本地的语料下标；回不来就说明索引与语料不是同一批。"""
        position = self._positions.get(chunk_id)
        if position is None:
            raise ValueError(
                f"向量库返回了语料里没有的 chunk：{chunk_id}（索引与当前语料不一致，需重建索引）"
            )
        return position

    async def _rerank(self, question: str, candidates: list[ScoredChunk]) -> list[ScoredChunk]:
        """精排：只对候选做，且严格校验返回的下标 —— 错位比不重排更危险。"""
        if not self.config.enable_rerank:
            return candidates
        if self.reranker is None:  # pragma: no cover - __post_init__ 已挡
            raise RuntimeError("rerank 开关已开但没有注入 reranker")
        documents = [self.corpus[candidate.chunk_index].text for candidate in candidates]
        response = await self.reranker.rerank(
            question, documents, top_n=min(self.config.rerank_top_n, len(documents))
        )
        seen: set[int] = set()
        reranked: list[ScoredChunk] = []
        for result in response.results:
            if not 0 <= result.index < len(candidates):
                raise ValueError(f"重排返回的下标 {result.index} 越界（候选 {len(candidates)} 条）")
            if result.index in seen:
                raise ValueError(f"重排返回了重复下标 {result.index}：无法确定排序")
            seen.add(result.index)
            reranked.append(
                ScoredChunk(
                    chunk_index=candidates[result.index].chunk_index,
                    score=result.score,
                    source="rerank",
                )
            )
        return reranked


def _elapsed_ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000, 3)


def _embedder_of(pipeline: RetrievalPipeline) -> EmbeddingModel:
    """取嵌入模型：`__post_init__` 已挡住「开了 dense 却没给模型」，
    这里再明确报一次错，是为了不让类型检查与运行期假设悄悄分叉。"""
    embedder = pipeline.embedder
    if embedder is None:  # pragma: no cover - 由 __post_init__ 保证
        raise RuntimeError("dense 通路缺少 embedder")
    return embedder
