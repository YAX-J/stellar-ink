"""混合检索的阈值标定：模型只调用一次，网格扫描复用原始排名。

两个通路先各自过滤，再按生产管线的 RRF 和文档去重规则评估。
验收集只用于评估冻结后的阈值，禁止参与网格生成或推荐。
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from app.providers.base import EmbeddingModel
from app.rag.eval_runner import EvalDataset
from app.rag.pipeline import IndexedChunk
from app.rag.retrieval import Bm25Index, ScoredChunk, dense_scores, reciprocal_rank_fusion


@dataclass(frozen=True)
class Observation:
    expected: frozenset[int]
    sparse: tuple[ScoredChunk, ...]
    dense: tuple[ScoredChunk, ...]


@dataclass(frozen=True)
class Scope:
    chunks: tuple[IndexedChunk, ...]
    rows: tuple[Observation, ...]


@dataclass(frozen=True)
class Quality:
    recall: float
    hit_rate: float
    refusal_rate: float
    false_refusal_rate: float


async def observe(
    dataset: EvalDataset, chunks: list[IndexedChunk], embedder: EmbeddingModel,
    *, candidate_k: int = 30,
) -> dict[str, Scope]:
    """同时覆盖评测的文章范围与问答的文章加笔记范围，不读种子回退。"""
    if not chunks or not dataset.cases:
        raise ValueError("标定需要非空语料和题集")
    vectors = (await embedder.embed([chunk.text for chunk in chunks])).vectors
    queries = (await embedder.embed([case.question for case in dataset.cases])).vectors
    if len(vectors) != len(chunks) or len(queries) != len(dataset.cases):
        raise ValueError("标定嵌入数量与输入不一致")
    scopes: dict[str, Scope] = {}
    for name, positions in (
        ("all", list(range(len(chunks)))),
        ("articles", [i for i, chunk in enumerate(chunks) if chunk.kind == "post"]),
    ):
        selected = tuple(chunks[i] for i in positions)
        sparse = Bm25Index().fit([chunk.text for chunk in selected])
        selected_vectors = [vectors[i] for i in positions]
        rows = tuple(
            Observation(
                expected=frozenset(case.expected_post_ids),
                sparse=tuple(sparse.search(case.question, top_k=candidate_k)),
                dense=tuple(dense_scores(query, selected_vectors, top_k=candidate_k)),
            )
            for case, query in zip(dataset.cases, queries, strict=True)
        )
        scopes[name] = Scope(selected, rows)
    return scopes


def quality(scope: Scope, sparse_floor: float, dense_floor: float, *, top_k: int = 5) -> Quality:
    """使用与检索管线相同的过滤、融合、文档去重顺序。"""
    recalls: list[float] = []
    hit_count = false_refusals = refusals = negatives = 0
    for row in scope.rows:
        paths = [
            [hit for hit in row.sparse if hit.score >= sparse_floor],
            [hit for hit in row.dense if dense_floor <= 0 or hit.score >= dense_floor],
        ]
        accepted: list[tuple[str, int]] = []
        for hit in reciprocal_rank_fusion(paths, top_k=30):
            chunk = scope.chunks[hit.chunk_index]
            key = (chunk.kind, chunk.post_id)
            if key not in accepted:
                accepted.append(key)
            if len(accepted) >= top_k:
                break
        if row.expected:
            found = row.expected & {post_id for kind, post_id in accepted if kind == "post"}
            recalls.append(len(found) / len(row.expected))
            hit_count += bool(found)
            false_refusals += not accepted
        else:
            negatives += 1
            refusals += not accepted
    if not recalls or not negatives:
        raise ValueError("标定与验收题集都必须同时包含有答案题和无答案题")
    return Quality(
        sum(recalls) / len(recalls), hit_count / len(recalls),
        refusals / negatives, false_refusals / len(recalls),
    )


def preserves_answers(result: Quality, baseline: Quality) -> bool:
    """预先固定约束：不新增误拒，命中率不下降，Recall 最多下降 2.5 个百分点。"""
    return (
        result.false_refusal_rate <= baseline.false_refusal_rate
        and result.hit_rate >= baseline.hit_rate
        and result.recall >= baseline.recall - 0.025 - 1e-9
    )


def recommend(scopes: dict[str, Scope]) -> tuple[float, float] | None:
    """只从校准集选阈值：两种语料范围都保住答案，再最大化最差拒答率。"""
    baselines = {name: quality(scope, 0, 0) for name, scope in scopes.items()}
    # 使用可读的小数网格，向上跨过观测值；避免推荐紧贴某题的六位小数。
    sparse_grid = sorted({0.0} | {
        math.ceil(row.sparse[0].score * 10 + 1e-6) / 10
        for scope in scopes.values() for row in scope.rows if row.sparse
    })
    dense_grid = sorted({0.0} | {
        math.ceil(row.dense[0].score * 1000 + 1e-6) / 1000
        for scope in scopes.values() for row in scope.rows if row.dense
    })
    feasible: list[tuple[tuple[float, ...], tuple[float, float]]] = []
    for sparse_floor in sparse_grid:
        for dense_floor in dense_grid:
            results = {name: quality(scope, sparse_floor, dense_floor)
                       for name, scope in scopes.items()}
            if all(preserves_answers(result, baselines[name]) for name, result in results.items()):
                score = (
                    min(result.refusal_rate for result in results.values()),
                    min(result.recall for result in results.values()),
                    -sparse_floor, -dense_floor,
                )
                feasible.append((score, (sparse_floor, dense_floor)))
    if not feasible or max(feasible)[0][0] <= min(q.refusal_rate for q in baselines.values()):
        return None
    return max(feasible)[1]
