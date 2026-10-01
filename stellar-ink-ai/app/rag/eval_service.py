"""评测服务：把「一次评测请求」变成一张对比表 + 逐题明细。

分工：`app/rag/eval_runner.py` 负责跑（可被命令行/接口/测试共用），
本模块只负责「请求 → 语料 + 策略 + 模型」的装配与结果整形。
这样命令行脚本、HTTP 接口、将来的定时任务都走同一条路径。

语料与模型**由调用方注入**（`EvalCorpus` / `EvalModels`），本模块不自己去读数据库、
也不自己挑模型 —— 这里最容易出的事就是「脚本用 Fake、接口用真实模型」，
于是同一句「评测 Recall@1 = 0.83」在两边含义不同却看起来一样。

语料与数据集从哪来（这一层必须说清，否则「评测数字」会失去意义）：
- **数据集**：仓库里的黄金集 fixture（`tests/fixtures/eval/golden_v1.json`），
  与 `scripts/eval_local_baseline.py` 用的是同一份；将来改由 `ai_eval_dataset` 表提供时只换这一处。
- **语料**：种子内容包 `deploy/sql/02_init-data.sql`（`seed_corpus()`）。生产环境没有这个仓库文件，
  因此响应里带 `corpus_source`，将来换成 Java 推过来的真实文章时只换 `seed_corpus()`。
- **模型**：`EvalModels`。真实模型评测走面板配置（`source=panel`）；
  命令行与 fixture 生成器显式用 `fake_models()`（`source=fake`），
  响应里的 `models` 与 `notes` 会如实说明，避免把 Fake 的数字当成真实质量。
"""

from __future__ import annotations

import time
from collections.abc import Sequence
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any

from app.providers.base import EmbeddingModel, RerankModel
from app.providers.fake import FakeProvider
from app.rag import corpus as corpus_module
from app.rag.eval_runner import EvalDataset, StrategySpec, run_dataset
from app.rag.graph import GraphRetriever, graph_from_payload
from app.rag.metrics import DEFAULT_KS, CaseResult
from app.rag.pipeline import IndexedChunk, RetrievalConfig, RetrievalPipeline
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


class _NoGraphRetriever:
    """开了图检索却**没带图**时的替身：如实拒答，并说清「这次没图」。

    为什么不用一个空图顶替：空图会让这一行显示成「Recall@1 = 0」，
    读起来像「图检索效果很差」，实际是「这次根本没跑图检索」—— 那是两件事。
    """

    def __init__(self) -> None:
        self.last_note = (
            "本次请求开了 enableGraph 但没有带 graph（一次 /wiki/claims 返回体）——"
            "这一行不代表图检索的效果，请带上图后重跑。"
        )

    async def retrieve(self, question: str, *, top_k: int) -> Any:
        from app.rag.eval_runner import RetrievalOutcome

        return RetrievalOutcome(posts=[], chunks=[], cited_chunks=[], refused=True, latency_ms=0.0)


@dataclass(frozen=True, slots=True)
class EvalCorpus:
    """评测语料。`source` 会原样出现在响应里，所以它是**给人看的**（要能对上仓库文件）。"""

    chunks: list[IndexedChunk]
    source: str
    posts: int


@dataclass(frozen=True, slots=True)
class EvalModels:
    """评测要用的模型。

    两者都可以是 `None`：纯稀疏策略一次模型都不碰，因此「只跑 BM25」不该被
    「没配嵌入模型」挡住 —— 否则最省钱的对照实验反而跑不起来。
    """

    embedder: EmbeddingModel | None = None
    reranker: RerankModel | None = None
    source: EvalModelSource = EvalModelSource.FAKE
    #: 实际用到的模型名（写进 notes，让人知道「这个 Dense 是谁算的」）
    names: tuple[str, ...] = ()


def seed_corpus() -> EvalCorpus:
    """当前语料：种子内容包（与问答、Agent 共用同一份缓存与切块口径）。"""
    return EvalCorpus(
        chunks=corpus_module.cached_corpus(),
        source=corpus_module.corpus_source(),
        posts=len(corpus_module.cached_posts()),
    )


def fake_models() -> EvalModels:
    """离线模型（哈希伪向量 + 伪重排）。

    **必须显式取用**：命令行脚本与 fixture 生成器用它，业务接口不用 ——
    这样「面板没配模型」与「故意用桩」在代码里就是两个不同的调用，
    不会因为某处忘了传参而悄悄退化成桩。
    """
    provider = FakeProvider()
    return EvalModels(
        embedder=provider,
        reranker=provider,
        source=EvalModelSource.FAKE,
        names=("fake",),
    )


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


def strategy_specs(request: EvalRunRequest) -> list[EvalStrategySpec]:
    """请求里的策略（为空则标准五组）；重复 key 直接拒绝。

    重复 key 会让对比表两列同名、逐题明细无法区分策略 —— 与其在表里显示两列一样的名字，
    不如在跑之前就说清楚。
    """
    specs = list(request.strategies) or list(DEFAULT_STRATEGIES)
    keys = [spec.key for spec in specs]
    duplicated = sorted({key for key in keys if keys.count(key) > 1})
    if duplicated:
        raise ValueError(f"策略 key 重复：{duplicated}")
    return specs


def required_roles(specs: Sequence[EvalStrategySpec]) -> list[str]:
    """这些策略要用到哪些模型角色。

    接口层据它在**跑之前**预检：一轮评测要先嵌入整个语料，
    等第一路 Dense 跑到一半才发现「嵌入模型没配」的话，用户是白等几十秒再拿到错误。
    """
    roles: list[str] = []
    if any(spec.enable_dense for spec in specs):
        roles.append("embedding")
    if any(spec.enable_rerank for spec in specs):
        roles.append("rerank")
    return roles


def _require_models(specs: Sequence[EvalStrategySpec], models: EvalModels) -> None:
    """策略要用模型但调用方没给：在这里说清是哪一路。

    不做这件事的话，`RetrievalPipeline` 会抛「启用 dense 通路必须注入 embedder」——
    那句话对写管道的人有意义，对点面板的人没有。
    """
    if any(spec.enable_dense for spec in specs) and models.embedder is None:
        raise ValueError("有策略启用 dense 通路，但没有可用嵌入模型：请在面板配置 embedding 角色")
    if any(spec.enable_rerank for spec in specs) and models.reranker is None:
        raise ValueError("有策略启用 rerank，但没有可用重排模型：请在面板配置 rerank 角色")


async def run_evaluation(
    request: EvalRunRequest, *, corpus: EvalCorpus, models: EvalModels
) -> EvalRunOutcome:
    """跑一轮评测。失败一律抛 ValueError（接口层转 400），不吞成空结果。"""
    started = time.perf_counter()
    dataset_path = default_dataset_path(request.dataset)
    dataset = EvalDataset.load(dataset_path)
    if request.max_cases is not None:
        dataset = replace(dataset, cases=dataset.cases[: request.max_cases])

    chunks = corpus.chunks
    if not chunks:
        raise ValueError("语料为空：没有可检索的子块，评测没有意义")

    specs = strategy_specs(request)
    _require_models(specs, models)
    graph_index = graph_from_payload(request.graph) if request.graph else None
    plans: list[StrategySpec] = []
    summaries: list[tuple[str, str]] = []
    for spec in specs:
        if spec.enable_graph:
            # 图检索**不经过 RetrievalPipeline**：它不走召回+融合那套，
            # 而是「命中实体 → 沿共现边一跳」。硬塞进 pipeline 只会让两边都变形
            retriever = GraphRetriever(graph_index) if graph_index else _NoGraphRetriever()
            description = "graph(local)" + ("" if graph_index else " · ⚠️ 本次没带图")
            plans.append(
                StrategySpec(spec.key, retriever, top_k=spec.top_k, description=description)
            )
            summaries.append((spec.key, description))
            continue
        pipeline = RetrievalPipeline(
            corpus=chunks,
            config=_config_of(spec),
            embedder=models.embedder if spec.enable_dense else None,
            reranker=models.reranker if spec.enable_rerank else None,
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
    error_count = sum(1 for case in result.cases if case.error)
    return EvalRunOutcome(
        dataset_name=dataset.name,
        dataset_description=dataset.description,
        corpus_source=corpus.source,
        posts=corpus.posts,
        chunks=len(chunks),
        models=models.source,
        ks=tuple(DEFAULT_KS),
        strategies=summaries,
        per_strategy=result.per_strategy,
        cases=result.cases,
        elapsed_ms=round((time.perf_counter() - started) * 1000, 3),
        notes=_notes(models, error_count=error_count),
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


def _notes(models: EvalModels, *, error_count: int = 0) -> list[str]:
    """把「这些数字能说明什么、不能说明什么」写进响应，别让面板用户自己猜。

    第一条随模型来源变化，这是整个评测台最容易误读的地方：同样是「Dense 列 0.9」，
    离线伪向量和真实嵌入模型完全是两回事，而表格长得一模一样。
    """
    if models.source is EvalModelSource.NONE:
        head = (
            "本次没有用到模型：所选策略只有稀疏（BM25）召回，"
            "这组数字衡量的是词法匹配，与模型质量无关。"
        )
        tail = (
            "要比较向量与重排，先在「AI 实验室 → 模型配置」里配好 "
            "embedding / rerank 角色，再勾上对应策略。"
        )
    elif models.source is EvalModelSource.FAKE:
        head = (
            "本次使用 FakeProvider 的哈希伪向量：Dense 两列只证明向量通路接对了，"
            "不代表真实语义质量。"
        )
        tail = "想看真实质量：在「AI 实验室 → 模型配置」里配好 embedding / rerank 角色后重跑本页。"
    else:
        names = "、".join(models.names) or "未记录模型名"
        head = f"本次向量与重排来自面板配置的模型（{names}）：这两列反映的是真实链路质量。"
        tail = "拒答阈值（minScore / minDenseScore）随嵌入模型而变，换模型后必须用本页重新标定。"

    notes = [
        head,
        "拒答率的分母是「无答案题」，误拒率的分母是「有答案题」——两者不能相加。",
        "BM25 的 minScoreRatio 只提精度、永远不会让结果为空；"
        "能拒答的只有 minScore 与 minDenseScore。",
        tail,
    ]
    if error_count:
        # 这条必须显眼：出错的那一行**看起来**像「这个策略全错」，实际是上游限流/超时。
        # 不写进 notes 的话，面板上的错误结论会直接被当成检索质量结论。
        notes.insert(
            0,
            f"⚠ 本轮有 {error_count} 道题因上游失败（限流/超时/5xx）被记为「拒答」："
            "这些行的指标不可用，请降低策略数或稍后重跑；"
            "逐题原因见服务端日志 `评测单题失败`。",
        )
    return notes
