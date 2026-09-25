"""评测服务：把「一次评测请求」变成一张对比表 + 逐题明细。

分工：`app/rag/eval_runner.py` 负责跑（可被命令行/接口/测试共用），
本模块只负责「请求 → 语料 + 策略 + 模型」的装配与结果整形。
这样命令行脚本、HTTP 接口、将来的定时任务都走同一条路径。

语料与数据集从哪来（这一层必须说清，否则「评测数字」会失去意义）：
- **数据集**：仓库里的黄金集 fixture（`tests/fixtures/eval/golden_v1.json`），
  与 `scripts/eval_local_baseline.py` 用的是同一份；将来改由 `ai_eval_dataset` 表提供时只换这一处。
- **语料**：种子内容包 `deploy/sql/02_init-data.sql`（29 篇文章）。生产环境没有这个仓库文件，
  因此接口把「语料来源」写进响应（`corpus_source`），并允许将来换成 Java 推过来的真实文章。
- **模型**：目前只用 `FakeProvider`（确定性、零成本）。真实模型评测要等 Java 传 Provider
  运行期配置；响应里的 `models` 与 `notes` 会如实说明，避免把 Fake 的数字当成真实质量。
"""

from __future__ import annotations

import time
from collections.abc import Sequence
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any

from app.providers.fake import FakeProvider
from app.rag.eval_runner import EvalDataset, StrategySpec, run_dataset
from app.rag.metrics import DEFAULT_KS, CaseResult
from app.rag.pipeline import IndexedChunk, RetrievalConfig, RetrievalPipeline, build_corpus
from app.rag.seed_corpus import SeedPost, default_seed_sql, load_seed_posts
from app.schemas.eval import (
    EvalCaseResultRow,
    EvalModelSource,
    EvalRunRequest,
    EvalRunResponse,
    EvalStrategySpec,
    EvalStrategySummary,
)

#: 数据集标识 → fixture 文件（将来可换成 DB 来源）
DATASET_FILES: dict[str, str] = {"golden_v1": "golden_v1.json"}

#: 标准五组：与 `scripts/compare_strategies.py` 一致，命令行与面板不该有两套默认值
DEFAULT_STRATEGIES: tuple[EvalStrategySpec, ...] = (
    EvalStrategySpec(key="sparse", enable_sparse=True, enable_dense=False),
    EvalStrategySpec(key="dense", enable_sparse=False, enable_dense=True),
    EvalStrategySpec(key="hybrid", enable_sparse=True, enable_dense=True),
    EvalStrategySpec(
        key="hybrid+rerank", enable_sparse=True, enable_dense=True, enable_rerank=True
    ),
    EvalStrategySpec(
        key="sparse+floor",
        enable_sparse=True,
        enable_dense=False,
        min_score=11.0,
        min_score_ratio=0.5,
    ),
)

_FIXTURE_RELATIVE = Path("tests") / "fixtures" / "eval"


def default_dataset_path(name: str) -> Path:
    """按数据集标识找到 fixture；标识不支持时给出可操作的错误。"""
    filename = DATASET_FILES.get(name)
    if filename is None:
        supported = "/".join(sorted(DATASET_FILES))
        raise ValueError(f"不支持的数据集：{name}（当前只有 {supported}）")
    for parent in Path(__file__).resolve().parents:
        candidate = parent / _FIXTURE_RELATIVE / filename
        if candidate.is_file():
            return candidate
    raise ValueError(f"数据集文件缺失：{_FIXTURE_RELATIVE / filename}")


@dataclass(frozen=True, slots=True)
class EvalRunOutcome:
    """一次评测的完整结果（接口层直接转成响应契约）。"""

    dataset_name: str
    dataset_description: str
    corpus_source: str
    posts: int
    chunks: int
    models: EvalModelSource
    ks: tuple[int, ...]
    strategies: list[tuple[str, str]]
    per_strategy: dict[str, dict[str, Any]]
    cases: list[CaseResult]
    elapsed_ms: float
    notes: list[str]


def _config_of(spec: EvalStrategySpec) -> RetrievalConfig:
    return RetrievalConfig(
        enable_sparse=spec.enable_sparse,
        enable_dense=spec.enable_dense,
        enable_rerank=spec.enable_rerank,
        candidate_k=spec.candidate_k,
        sparse_weight=spec.sparse_weight,
        dense_weight=spec.dense_weight,
        rrf_k=spec.rrf_k,
        min_score=spec.min_score,
        min_score_ratio=spec.min_score_ratio,
        min_dense_score=spec.min_dense_score,
        rerank_top_n=spec.rerank_top_n,
        label=spec.key,
    )


def _strategies_of(request: EvalRunRequest) -> list[EvalStrategySpec]:
    specs = list(request.strategies) or list(DEFAULT_STRATEGIES)
    keys = [spec.key for spec in specs]
    duplicated = sorted({key for key in keys if keys.count(key) > 1})
    if duplicated:
        # 重复 key 会让对比表两列同名、且逐题明细无法区分策略
        raise ValueError(f"策略 key 重复：{duplicated}")
    return specs


def _corpus_of(posts: Sequence[SeedPost]) -> tuple[list[IndexedChunk], str]:
    chunks = build_corpus(list(posts))
    source = f"seed-sql:{default_seed_sql().name}"
    return chunks, source


async def run_evaluation(request: EvalRunRequest) -> EvalRunOutcome:
    """跑一轮评测。失败一律抛 ValueError（接口层转 400），不吞成空结果。"""
    started = time.perf_counter()
    dataset_path = default_dataset_path(request.dataset)
    dataset = EvalDataset.load(dataset_path)
    if request.max_cases is not None:
        dataset = replace(dataset, cases=dataset.cases[: request.max_cases])

    posts = load_seed_posts()
    chunks, corpus_source = _corpus_of(posts)
    if not chunks:
        raise ValueError("语料为空：没有可检索的子块，评测没有意义")

    provider = FakeProvider()
    specs = _strategies_of(request)
    plans: list[StrategySpec] = []
    summaries: list[tuple[str, str]] = []
    for spec in specs:
        pipeline = RetrievalPipeline(
            corpus=chunks,
            config=_config_of(spec),
            embedder=provider if spec.enable_dense else None,
            reranker=provider if spec.enable_rerank else None,
        )
        description = ", ".join(
            part
            for part in (
                "+".join(
                    name
                    for name, enabled in (
                        ("sparse", spec.enable_sparse),
                        ("dense", spec.enable_dense),
                    )
                    if enabled
                ),
                "rerank" if spec.enable_rerank else "",
                f"candidateK={spec.candidate_k}",
                f"minScore={spec.min_score}" if spec.min_score else "",
                f"minDenseScore={spec.min_dense_score}" if spec.min_dense_score else "",
            )
            if part
        )
        plans.append(StrategySpec(spec.key, pipeline, top_k=spec.top_k, description=description))
        summaries.append((spec.key, description))

    result = await run_dataset(dataset, plans, ks=DEFAULT_KS)
    return EvalRunOutcome(
        dataset_name=dataset.name,
        dataset_description=dataset.description,
        corpus_source=corpus_source,
        posts=len(load_seed_posts()),
        chunks=len(chunks),
        models=EvalModelSource.FAKE,
        ks=tuple(DEFAULT_KS),
        strategies=summaries,
        per_strategy=result.per_strategy,
        cases=result.cases,
        elapsed_ms=round((time.perf_counter() - started) * 1000, 3),
        notes=_notes(),
    )


def to_response(outcome: EvalRunOutcome) -> EvalRunResponse:
    """把运行结果转成对外契约。

    单独抽出来是为了让「跑一次评测的命令行/脚本」也能产出**与接口逐字节一致**的响应 ——
    生成的 fixture（`scripts/gen_eval_response_fixture.py`）因此可以放心当契约样例用。
    """
    return EvalRunResponse(
        dataset=outcome.dataset_name,
        dataset_description=outcome.dataset_description,
        corpus_source=outcome.corpus_source,
        corpus_posts=outcome.posts,
        corpus_chunks=outcome.chunks,
        models=outcome.models,
        ks=list(outcome.ks),
        strategies=[
            EvalStrategySummary(key=key, description=description)
            for key, description in outcome.strategies
        ],
        per_strategy=outcome.per_strategy,
        cases=[
            EvalCaseResultRow(
                case_id=case.case_id,
                strategy=case.strategy,
                question=case.question,
                case_type=case.case_type,
                retrieved_posts=list(case.retrieved_posts),
                relevant_posts=list(case.relevant_posts),
                refused=case.refused,
                latency_ms=case.latency_ms,
            )
            for case in outcome.cases
        ],
        elapsed_ms=outcome.elapsed_ms,
        notes=outcome.notes,
    )


def _notes() -> list[str]:
    """把「这些数字能说明什么、不能说明什么」写进响应，别让面板用户自己猜。"""
    return [
        "本次使用 FakeProvider 的哈希伪向量：Dense 两列只证明向量通路接对了，不代表真实语义质量。",
        "拒答率的分母是「无答案题」，误拒率的分母是「有答案题」——两者不能相加。",
        "BM25 的 minScoreRatio 只提精度、永远不会让结果为空；"
        "能拒答的只有 minScore 与 minDenseScore。",
        "真实模型（bge-m3 / bge-reranker）评测需等 Provider 运行期配置从 Java 传入后开放。",
    ]
