"""评测运行器：把黄金集 × 策略跑成一张可对比的指标表。

分工：
- `EvalCase` / `EvalDataset`：黄金集的数据模型（可存 JSON，也可来自 `ai_eval_*` 表）；
- `Retriever`：被测对象的接口 —— 调用方注入（真实管线、Fake、或某个固定策略）；
- `run_dataset`：遍历题目 × 策略，收集 `CaseResult`；
- `compare_strategies`（见 `metrics.py`）负责出表。

为什么检索器用注入而不是在这里 new 出来：评测台要对比「单路 Dense / 单路 Sparse /
混合 / 混合+重排」等**多组配置**，每组是同一段编排、不同参数。
注入让运行器不必知道管线细节，也保证「跑评测」这条路不会绕过真实检索逻辑。

数据落库的形状与 `deploy/sql/10_ai-schema.sql` 的 `ai_eval_*` 表一致：
`metrics_json` 放指标、`case_result_json` 放逐题明细（前端下钻用）。
"""

from __future__ import annotations

import json
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from app.rag.metrics import DEFAULT_KS, CaseResult, compare_strategies


class Retriever(Protocol):
    """被测检索器：给一个问题，返回候选与（可选）引用片段。

    返回的 `posts` 是**按相关性降序**的 post id 列表；`chunks` 是本次实际送进上下文的
    chunk id（用于算引用准确率）。延迟由实现自己测（`latency_ms`）。
    """

    async def retrieve(self, question: str, *, top_k: int) -> RetrievalOutcome: ...


@dataclass(frozen=True, slots=True)
class RetrievalOutcome:
    posts: list[int]
    chunks: list[str] = field(default_factory=list)
    cited_chunks: list[str] = field(default_factory=list)
    refused: bool = False
    latency_ms: float = 0.0

    def __post_init__(self) -> None:
        if self.latency_ms < 0:
            raise ValueError("latency_ms 不能为负")


@dataclass(frozen=True, slots=True)
class EvalCase:
    """一道题。

    `expected_post_ids` 为空表示**无答案题**：这类题的正确行为是拒答，
    因此不参与召回率统计，只进拒答率（见 metrics 的聚合口径）。
    """

    case_id: str
    question: str
    expected_post_ids: list[int] = field(default_factory=list)
    expected_answer: str | None = None
    #: 可选的分级相关标注：{postId: 增益}，未标时按二值处理
    graded_relevance: dict[int, float] | None = None
    #: 期望命中的 chunk（调试切块质量时用，不参与策略对比主表）
    expected_chunk_ids: list[str] = field(default_factory=list)

    @property
    def answerable(self) -> bool:
        return bool(self.expected_post_ids)

    def to_row(self) -> dict[str, Any]:
        return {
            "caseId": self.case_id,
            "question": self.question,
            "expectedPostIds": list(self.expected_post_ids),
            "expectedAnswer": self.expected_answer,
            "gradedRelevance": self.graded_relevance,
            "expectedChunkIds": list(self.expected_chunk_ids),
        }

    @staticmethod
    def from_row(row: dict[str, Any]) -> EvalCase:
        """从 JSON/DB 行构造；缺字段给出可定位的错误而不是静默用默认值。"""
        case_id = str(row.get("caseId") or "").strip()
        question = str(row.get("question") or "").strip()
        if not case_id or not question:
            raise ValueError("评测题必须有 caseId 与 question")
        graded = row.get("gradedRelevance")
        return EvalCase(
            case_id=case_id,
            question=question,
            expected_post_ids=[int(item) for item in (row.get("expectedPostIds") or [])],
            expected_answer=row.get("expectedAnswer"),
            graded_relevance=(
                {int(key): float(value) for key, value in graded.items()} if graded else None
            ),
            expected_chunk_ids=[str(item) for item in (row.get("expectedChunkIds") or [])],
        )


@dataclass(frozen=True, slots=True)
class EvalDataset:
    """黄金集。`name` 与 `ai_eval_dataset.name` 对应，便于落库。"""

    name: str
    cases: list[EvalCase]
    description: str = ""

    def __post_init__(self) -> None:
        if not self.name.strip():
            raise ValueError("数据集必须有名字")
        if not self.cases:
            raise ValueError("数据集至少要有一道题")
        seen: set[str] = set()
        for case in self.cases:
            if case.case_id in seen:
                raise ValueError(f"caseId 重复：{case.case_id}（会导致聚合时重复计分）")
            seen.add(case.case_id)

    @property
    def answerable_count(self) -> int:
        return sum(1 for case in self.cases if case.answerable)

    @property
    def unanswerable_count(self) -> int:
        return len(self.cases) - self.answerable_count

    def summary(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "cases": len(self.cases),
            "answerableCases": self.answerable_count,
            "unanswerableCases": self.unanswerable_count,
        }

    def to_json(self) -> str:
        return json.dumps(
            {
                "name": self.name,
                "description": self.description,
                "cases": [case.to_row() for case in self.cases],
            },
            ensure_ascii=False,
            indent=2,
        )

    @staticmethod
    def from_json(payload: str) -> EvalDataset:
        data = json.loads(payload)
        return EvalDataset(
            name=str(data.get("name") or "").strip(),
            description=str(data.get("description") or ""),
            cases=[EvalCase.from_row(row) for row in data.get("cases") or []],
        )

    @staticmethod
    def load(path: str | Path) -> EvalDataset:
        return EvalDataset.from_json(Path(path).read_text(encoding="utf-8"))


@dataclass(frozen=True, slots=True)
class StrategySpec:
    """一组被测配置：名字给对比表用，`retriever` 是实现，`top_k` 是评估深度。"""

    key: str
    retriever: Retriever
    top_k: int = 10
    description: str = ""

    def __post_init__(self) -> None:
        if not self.key.strip():
            raise ValueError("策略必须有 key（对比表的列名）")
        if self.top_k <= 0:
            raise ValueError("top_k 必须为正")


@dataclass(frozen=True, slots=True)
class EvalRunResult:
    """一次评测的完整结果：能直接映射到 `ai_eval_run` 一行。"""

    dataset: str
    ks: tuple[int, ...]
    per_strategy: dict[str, dict[str, Any]]
    cases: list[CaseResult]

    def to_row(self) -> dict[str, Any]:
        """落库形态：指标与逐题明细分开存（前端分别用于对比表与下钻）。"""
        return {
            "dataset": self.dataset,
            "metricsJson": self.per_strategy,
            "caseResultJson": [
                {
                    "caseId": case.case_id,
                    "strategy": case.strategy,
                    "question": case.question,
                    "caseType": case.case_type,
                    "retrievedPosts": case.retrieved_posts,
                    "relevantPosts": case.relevant_posts,
                    "refused": case.refused,
                    "latencyMs": case.latency_ms,
                }
                for case in self.cases
            ],
        }


async def run_dataset(
    dataset: EvalDataset,
    strategies: Sequence[StrategySpec],
    *,
    ks: Sequence[int] = DEFAULT_KS,
    on_case: Callable[[int, int], None] | None = None,
) -> EvalRunResult:
    """跑完「题目 × 策略」的全部组合。

    `on_case(done, total)` 用于上报进度（前端跑评测是长任务，需要进度而不是转圈）。
    单题失败**不中断整轮**：记一条 `refused=True` 且延迟为 0 的结果，
    否则一道题的网络抖动会让整次对比作废。
    """
    if not strategies:
        raise ValueError("至少要有一个策略")
    results: list[CaseResult] = []
    total = len(dataset.cases) * len(strategies)
    done = 0

    for strategy in strategies:
        for case in dataset.cases:
            outcome = await _retrieve_safely(strategy, case)
            results.append(
                CaseResult(
                    case_id=case.case_id,
                    strategy=strategy.key,
                    question=case.question,
                    case_type="answerable" if case.answerable else "unanswerable",
                    retrieved_posts=list(outcome.posts),
                    retrieved_chunks=list(outcome.chunks),
                    relevant_posts=list(case.expected_post_ids),
                    relevant_chunks=list(case.expected_chunk_ids),
                    cited_chunks=list(outcome.cited_chunks) or list(outcome.chunks),
                    refused=outcome.refused,
                    latency_ms=outcome.latency_ms,
                    graded_relevance=case.graded_relevance,
                )
            )
            done += 1
            if on_case is not None:
                on_case(done, total)

    return EvalRunResult(
        dataset=dataset.name,
        ks=tuple(ks),
        per_strategy=compare_strategies(results, ks),
        cases=results,
    )


async def _retrieve_safely(strategy: StrategySpec, case: EvalCase) -> RetrievalOutcome:
    """单题失败降级为「拒答 + 空结果」，并保留可读原因（不中断整轮评测）。"""
    try:
        return await strategy.retriever.retrieve(case.question, top_k=strategy.top_k)
    except Exception:  # noqa: BLE001 - 评测是批量任务：单题异常不该让整轮作废
        return RetrievalOutcome(posts=[], chunks=[], refused=True, latency_ms=0.0)


class ListRetriever:
    """固定答案的检索器：用于测试运行器本身，以及「基线对照」。"""

    def __init__(self, answers: dict[str, RetrievalOutcome]) -> None:
        self._answers = answers

    async def retrieve(self, question: str, *, top_k: int) -> RetrievalOutcome:
        outcome = self._answers.get(question)
        if outcome is None:
            return RetrievalOutcome(posts=[], chunks=[], refused=True)
        return RetrievalOutcome(
            posts=outcome.posts[:top_k],
            chunks=outcome.chunks,
            cited_chunks=outcome.cited_chunks,
            refused=outcome.refused,
            latency_ms=outcome.latency_ms,
        )


def datasets_available(directory: str | Path) -> Iterable[str]:
    """列目录下可用的黄金集文件名（供前端下拉选择）。"""
    path = Path(directory)
    if not path.is_dir():
        return []
    return sorted(item.name for item in path.glob("*.json"))
