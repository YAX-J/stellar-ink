"""评测指标：把「检索好不好」从主观感觉变成可回归的数字。

为什么这些必须是纯函数：评测结果要能对比不同策略（单路/多路、开不开重排、不同切块参数），
只要指标实现本身有一点不确定性（依赖顺序、随机、外部服务），对比就失去意义。
因此这一层不 import 任何向量库、模型或网络客户端。

指标口径（与 roadmap §10 对齐）：
- 检索：`Recall@K` / `Precision@K` / `MRR` / `NDCG@K`（支持二值相关与分级增益）
- 生成：引用准确率（引用是否真的指向被检索到的片段）、拒答率（无证据时是否明说）
- 性能：P50 / P95 延迟

关于「相关」的判定：本层只关心 **post 级别**（同一篇文章的多个 chunk 命中不重复计分），
因为用户感知的相关性是「这篇文章对不对」，而不是「这个片段排第几」。
chunk 级指标留给调试用（见 `chunk_recall`）。
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from typing import Any

#: 默认评估的 K 值：3 看「前三条有没有」，5/10 看召回面
DEFAULT_KS: tuple[int, ...] = (1, 3, 5, 10)


def _validate_k(k: int) -> None:
    if k <= 0:
        raise ValueError("k 必须为正")


def _unique_in_order(values: Iterable[int]) -> list[int]:
    """去重但保留首次出现顺序 —— 排序类指标依赖顺序，不能用 set。"""
    seen: set[int] = set()
    result: list[int] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


def recall_at_k(retrieved: Sequence[int], relevant: Sequence[int], k: int) -> float:
    """前 K 个结果覆盖了多少比例的相关项。

    无相关项（无答案题）时返回 `nan` 语义上是错的：这类题用拒答率评估，
    这里返回 0.0 会把它错误地算成「没召回」，所以调用方应先按 case_type 分流。
    """
    _validate_k(k)
    if not relevant:
        raise ValueError("无相关项时不该调用 recall_at_k：无答案题请用拒答率评估")
    top = set(_unique_in_order(retrieved)[:k])
    hits = len(top & set(relevant))
    return hits / len(set(relevant))


def precision_at_k(retrieved: Sequence[int], relevant: Sequence[int], k: int) -> float:
    """前 K 个结果里相关项占比。分母用实际返回条数（返回不足 K 时不虚增分母）。"""
    _validate_k(k)
    unique = _unique_in_order(retrieved)[:k]
    if not unique:
        return 0.0
    if not relevant:
        return 0.0
    return len(set(unique) & set(relevant)) / len(unique)


def reciprocal_rank(retrieved: Sequence[int], relevant: Sequence[int]) -> float:
    """第一个相关结果的名次倒数（1/rank）；没有命中返回 0。"""
    if not relevant:
        return 0.0
    relevant_set = set(relevant)
    for rank, item in enumerate(_unique_in_order(retrieved), start=1):
        if item in relevant_set:
            return 1.0 / rank
    return 0.0


def dcg(gains: Sequence[float]) -> float:
    """折损累计增益：`Σ gain_i / log2(i + 1)`（i 从 1 开始）。"""
    return sum(gain / math.log2(index + 1) for index, gain in enumerate(gains, start=1))


def ndcg_at_k(
    retrieved: Sequence[int],
    relevant: Sequence[int] | dict[int, float],
    k: int,
) -> float:
    """NDCG@K：同时考虑「命中多少」与「命中排多前」。

    支持两种相关标注：
    - `Sequence[int]`：二值相关（命中得 1 分）；
    - `dict[int, float]`：分级增益（如人工标注 0.5 / 1.0 / 2.0）。
    理想排序（IDCG）按增益降序排列，这是 NDCG 的标准定义。
    """
    _validate_k(k)
    gains = _gain_map(relevant)
    if not gains:
        return 0.0

    actual = [gains.get(item, 0.0) for item in _unique_in_order(retrieved)[:k]]
    ideal = sorted(gains.values(), reverse=True)[:k]
    ideal_score = dcg(ideal)
    if ideal_score == 0:
        return 0.0
    return dcg(actual) / ideal_score


def chunk_recall(retrieved_chunks: Sequence[str], relevant_chunks: Sequence[str]) -> float:
    """chunk 级召回：调试「切块切得好不好」时用，不参与策略对比主表。

    与 post 级指标的区别：这里同一篇文章的不同片段算作不同目标，
    因此能暴露「文章召回了，但召回的片段不是讲这件事的那一段」。
    """
    if not relevant_chunks:
        return 0.0
    return len(set(retrieved_chunks) & set(relevant_chunks)) / len(set(relevant_chunks))


def hit_rate(retrieved: Sequence[int], relevant: Sequence[int], k: int) -> float:
    """前 K 条是否至少命中一个相关项（0 或 1）。"""
    _validate_k(k)
    if not relevant:
        return 0.0
    return 1.0 if set(_unique_in_order(retrieved)[:k]) & set(relevant) else 0.0


def percentile(values: Sequence[float], ratio: float) -> float:
    """线性插值分位数（P50/P95 用）。空集合返回 0。"""
    if not values:
        return 0.0
    if not 0 <= ratio <= 1:
        raise ValueError("ratio 必须在 [0, 1]")
    ordered = sorted(values)
    if len(ordered) == 1:
        return float(ordered[0])
    position = ratio * (len(ordered) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return float(ordered[int(position)])
    weight = position - lower
    return float(ordered[lower] * (1 - weight) + ordered[upper] * weight)


@dataclass(frozen=True, slots=True)
class AnswerMetrics:
    """生成侧指标：回答是否「有据可依」。"""

    citation_count: int = 0
    citation_accuracy: float = 0.0
    refused: bool = False
    evidence_sufficient: bool = True


def citation_accuracy(cited_chunk_ids: Sequence[str], retrieved_chunk_ids: Sequence[str]) -> float:
    """引用准确率：回答里的引用中，有多少比例确实来自本次检索结果。

    为什么这是硬指标：模型「编出」一个不存在的引用时，用户点进去会是空的 ——
    这类错误比答案不够好更伤信任。
    """
    if not cited_chunk_ids:
        return 1.0  # 没有引用就没有错误引用（拒答场景属于正常）
    retrieved = set(retrieved_chunk_ids)
    valid = [chunk_id for chunk_id in cited_chunk_ids if chunk_id in retrieved]
    return len(valid) / len(cited_chunk_ids)


@dataclass(frozen=True, slots=True)
class CaseResult:
    """单题结果：既有检索指标，也有生成与性能信息。"""

    case_id: str
    strategy: str
    question: str
    case_type: str  # "answerable" | "unanswerable"
    retrieved_posts: list[int]
    retrieved_chunks: list[str]
    relevant_posts: list[int]
    relevant_chunks: list[str] = field(default_factory=list)
    cited_chunks: list[str] = field(default_factory=list)
    refused: bool = False
    latency_ms: float = 0.0
    #: 单题**基础设施失败**的可读原因（限流/超时/上游 5xx）。
    #: 为什么必须单独记：这类失败在 `run_dataset` 里被降级成「拒答」，若不记下来，
    #: 一次 429 会让整行指标变成 0 并显示成「这个策略全错」——
    #: 用户会去怀疑检索与提示词，而真因是模型服务限流（实测踩过）。
    error: str | None = None
    #: 每题可带自定义增益（分级相关），缺省用二值
    graded_relevance: dict[int, float] | None = None

    def metrics_at(self, k: int) -> dict[str, float]:
        """取该题在 K 下的各项指标；无答案题只产出拒答相关指标。"""
        base = {
            f"precision@{k}": precision_at_k(self.retrieved_posts, self.relevant_posts, k),
            f"ndcg@{k}": ndcg_at_k(
                self.retrieved_posts,
                self.graded_relevance if self.graded_relevance is not None else self.relevant_posts,
                k,
            ),
            f"hit@{k}": hit_rate(self.retrieved_posts, self.relevant_posts, k),
        }
        if self.relevant_posts:
            base[f"recall@{k}"] = recall_at_k(self.retrieved_posts, self.relevant_posts, k)
        return base

    def answer_metrics(self) -> dict[str, float]:
        return {
            "mrr": reciprocal_rank(self.retrieved_posts, self.relevant_posts),
            "citationAccuracy": citation_accuracy(self.cited_chunks, self.retrieved_chunks),
            "refused": 1.0 if self.refused else 0.0,
        }


def evaluate_strategy(
    results: Sequence[CaseResult], ks: Sequence[int] = DEFAULT_KS
) -> dict[str, Any]:
    """把一批单题结果聚合成一张对比表所需的指标。

    聚合规则（写清楚，避免不同人算出不同数）：
    - 检索指标只在**有相关标注的题**上求平均（无答案题没有召回可言）；
    - 拒答率在**无答案题**上求平均：这类题的正确行为就是明说「没有依据」；
    - 延迟在所有题上取 P50/P95。
    """
    if not results:
        return {"cases": 0}

    answerable = [case for case in results if case.relevant_posts]
    unanswerable = [case for case in results if not case.relevant_posts]

    metrics: dict[str, Any] = {
        "cases": len(results),
        "answerableCases": len(answerable),
        "unanswerableCases": len(unanswerable),
        "strategies": sorted({case.strategy for case in results}),
    }

    if answerable:
        for k in ks:
            for name in (f"recall@{k}", f"precision@{k}", f"ndcg@{k}", f"hit@{k}"):
                values = [case.metrics_at(k)[name] for case in answerable]
                metrics[name] = round(sum(values) / len(values), 4)
        metrics["mrr"] = round(
            sum(case.answer_metrics()["mrr"] for case in answerable) / len(answerable), 4
        )

    all_cases = list(results)
    metrics["citationAccuracy"] = round(
        sum(case.answer_metrics()["citationAccuracy"] for case in all_cases) / len(all_cases), 4
    )

    if unanswerable:
        # 注意分母是「无答案题」：有答案题拒答是错误，不该混进来
        metrics["refusalRate"] = round(
            sum(1.0 for case in unanswerable if case.refused) / len(unanswerable), 4
        )
        metrics["falseRefusalRate"] = (
            round(sum(1.0 for case in answerable if case.refused) / len(answerable), 4)
            if answerable
            else 0.0
        )
    else:
        metrics["refusalRate"] = None
        metrics["falseRefusalRate"] = (
            round(sum(1.0 for case in answerable if case.refused) / len(answerable), 4)
            if answerable
            else 0.0
        )

    latencies = [case.latency_ms for case in all_cases if case.latency_ms > 0]
    metrics["latencyP50"] = round(percentile(latencies, 0.5), 1)
    metrics["latencyP95"] = round(percentile(latencies, 0.95), 1)

    #: 因上游故障被降级成「拒答」的题数。**这一位非 0 时整行指标不可用**：
    #: 拒答率、误拒率都会被这些「假拒答」抬起来，而它们不代表模型或检索的质量。
    metrics["errorCount"] = sum(1 for case in results if case.error)

    return metrics


def compare_strategies(
    results: Sequence[CaseResult],
    ks: Sequence[int] = DEFAULT_KS,
) -> dict[str, Any]:
    """按策略分组，输出可直接渲染成对比表的 `{策略: 指标}`。

    前端实验室的「对比表」就是这个结构：一次跑多组配置，每组的指标并排看。
    """
    grouped: dict[str, list[CaseResult]] = {}
    for case in results:
        grouped.setdefault(case.strategy, []).append(case)
    return {strategy: evaluate_strategy(cases, ks) for strategy, cases in sorted(grouped.items())}


def _gain_map(relevant: Sequence[int] | dict[int, float]) -> dict[int, float]:
    if isinstance(relevant, dict):
        return {int(key): float(value) for key, value in relevant.items() if value > 0}
    return {int(item): 1.0 for item in relevant}
