"""评测指标：每个口径都用「手算得出来」的例子固定住。

指标实现错了最危险的地方在于「数字看起来合理」——召回率 0.62 你无法凭直觉判断它是
对了还是错了。因此这里的用例尽量用能心算的小例子（例如 2 个相关项、命中 1 个 = 0.5），
而不是「跑一遍看差不多」。
"""

from __future__ import annotations

import math

import pytest

from app.rag.metrics import (
    CaseResult,
    chunk_recall,
    citation_accuracy,
    compare_strategies,
    dcg,
    evaluate_strategy,
    hit_rate,
    ndcg_at_k,
    percentile,
    precision_at_k,
    recall_at_k,
    reciprocal_rank,
)

# --------------------------------------------------------------- 检索指标


def test_recall_at_k_counts_distinct_posts() -> None:
    """同一篇文章的多个 chunk 命中不算「多个相关项」。"""
    assert recall_at_k([7, 7, 9, 3], [7, 9], 3) == 1.0
    assert recall_at_k([7, 1, 2], [7, 9], 3) == 0.5
    assert recall_at_k([1, 2, 3], [7, 9], 3) == 0.0


def test_recall_respects_k_boundary() -> None:
    # 相关项在第 4 位，K=3 时还没出现
    assert recall_at_k([1, 2, 3, 9], [9], 3) == 0.0
    assert recall_at_k([1, 2, 3, 9], [9], 4) == 1.0


def test_recall_rejects_unanswerable_case() -> None:
    """无答案题没有「相关项」：必须由调用方分流，而不是静默算成 0。"""
    with pytest.raises(ValueError, match="无答案题"):
        recall_at_k([1, 2], [], 3)


def test_precision_uses_actual_returned_count_as_denominator() -> None:
    """只返回 2 条时，分母是 2 而不是 K —— 否则短结果会被系统性低估。"""
    assert precision_at_k([7, 9], [7, 9], 5) == 1.0
    assert precision_at_k([7, 1], [7], 5) == 0.5


def test_reciprocal_rank_uses_first_hit_only() -> None:
    assert reciprocal_rank([1, 2, 9], [9]) == pytest.approx(1 / 3)
    assert reciprocal_rank([9, 2, 3], [9]) == 1.0
    assert reciprocal_rank([1, 2, 3], [9]) == 0.0


def test_ndcg_matches_hand_computed_value() -> None:
    """手算：相关项在位置 2 → DCG = 1/log2(3) ≈ 0.6309；IDCG（位置 1）= 1。"""
    assert ndcg_at_k([1, 9], [9], 5) == pytest.approx(1 / math.log2(3), rel=1e-6)
    assert ndcg_at_k([9], [9], 5) == 1.0
    assert ndcg_at_k([1, 2], [9], 5) == 0.0


def test_ndcg_supports_graded_relevance() -> None:
    """分级增益：把最相关的那篇排前面，NDCG 应当更高。"""
    graded = {9: 2.0, 8: 1.0}

    better = ndcg_at_k([9, 8], graded, 5)
    worse = ndcg_at_k([8, 9], graded, 5)

    assert better == 1.0
    assert worse < better
    assert worse > 0


def test_ndcg_ignores_gains_beyond_k() -> None:
    graded = {9: 2.0, 8: 1.0}
    # K=1 时理想排序只用最高增益，实际也只取第一条
    assert ndcg_at_k([9, 8], graded, 1) == 1.0
    assert ndcg_at_k([8, 9], graded, 1) == pytest.approx(0.5)


def test_dcg_discounts_by_log2_position() -> None:
    assert dcg([1.0]) == pytest.approx(1.0)
    assert dcg([1.0, 1.0]) == pytest.approx(1 + 1 / math.log2(3))


def test_hit_rate_is_binary() -> None:
    assert hit_rate([1, 2, 9], [9], 3) == 1.0
    assert hit_rate([1, 2, 3], [9], 3) == 0.0
    assert hit_rate([1, 2, 3, 9], [9], 3) == 0.0


def test_chunk_recall_uses_chunk_identity() -> None:
    """chunk 级召回能暴露「文章对了但片段不对」。"""
    assert chunk_recall(["p1:c0", "p1:c5"], ["p1:c5", "p2:c1"]) == 0.5
    assert chunk_recall([], ["p1:c0"]) == 0.0


@pytest.mark.parametrize("value", [0, -1])
def test_k_must_be_positive(value: int) -> None:
    with pytest.raises(ValueError):
        recall_at_k([1], [1], value)
    with pytest.raises(ValueError):
        precision_at_k([1], [1], value)
    with pytest.raises(ValueError):
        ndcg_at_k([1], [1], value)
    with pytest.raises(ValueError):
        hit_rate([1], [1], value)


# --------------------------------------------------------------- 生成指标


def test_citation_accuracy_only_counts_retrieved_chunks() -> None:
    """模型编出的引用（不在检索结果里）必须被算成错误。"""
    assert citation_accuracy(["c1", "c2"], ["c1", "c2", "c3"]) == 1.0
    assert citation_accuracy(["c1", "c9"], ["c1", "c2"]) == 0.5
    # 没有引用时不算错（拒答是正常行为）
    assert citation_accuracy([], ["c1"]) == 1.0


def test_percentile_linear_interpolation() -> None:
    values = [100, 200, 300, 400]

    assert percentile(values, 0.5) == pytest.approx(250.0)
    assert percentile(values, 0.0) == 100
    assert percentile(values, 1.0) == 400
    assert percentile([], 0.5) == 0.0
    assert percentile([42], 0.95) == 42
    with pytest.raises(ValueError):
        percentile(values, 1.5)


# --------------------------------------------------------------- 聚合


def make_case(
    case_id: str,
    *,
    strategy: str = "hybrid",
    retrieved: list[int],
    relevant: list[int],
    refused: bool = False,
    latency_ms: float = 100.0,
    cited: list[str] | None = None,
    retrieved_chunks: list[str] | None = None,
) -> CaseResult:
    return CaseResult(
        case_id=case_id,
        strategy=strategy,
        question=f"问题 {case_id}",
        case_type="answerable" if relevant else "unanswerable",
        retrieved_posts=retrieved,
        retrieved_chunks=retrieved_chunks
        if retrieved_chunks is not None
        else [f"c{i}" for i in retrieved],
        relevant_posts=relevant,
        cited_chunks=cited or [],
        refused=refused,
        latency_ms=latency_ms,
    )


def test_evaluate_strategy_averages_only_annotated_cases() -> None:
    results = [
        make_case("a", retrieved=[9, 1], relevant=[9], latency_ms=100),
        make_case("b", retrieved=[1, 9], relevant=[9], latency_ms=300),
        make_case("c", retrieved=[2], relevant=[], refused=True, latency_ms=200),  # 无答案题
    ]

    metrics = evaluate_strategy(results, ks=(1, 3))

    assert metrics["cases"] == 3
    assert metrics["answerableCases"] == 2
    assert metrics["unanswerableCases"] == 1
    # 两题里一题 @1 命中 → 0.5
    assert metrics["recall@1"] == 0.5
    assert metrics["mrr"] == pytest.approx((1.0 + 0.5) / 2, abs=1e-4)
    assert metrics["latencyP50"] == 200.0
    assert metrics["latencyP95"] > 200.0


def test_evaluate_strategy_separates_refusal_rates() -> None:
    """拒答率只统计无答案题；有答案题拒答算「误拒」。"""
    results = [
        make_case("a", retrieved=[9], relevant=[9]),
        make_case("b", retrieved=[9], relevant=[9], refused=True),  # 误拒
        make_case("c", retrieved=[1], relevant=[], refused=True),  # 正确拒答
        make_case("d", retrieved=[1], relevant=[], refused=False),  # 该拒未拒
    ]

    metrics = evaluate_strategy(results)

    assert metrics["refusalRate"] == 0.5
    assert metrics["falseRefusalRate"] == 0.5


def test_evaluate_strategy_without_unanswerable_cases() -> None:
    results = [make_case("a", retrieved=[9], relevant=[9])]

    metrics = evaluate_strategy(results)

    assert metrics["unanswerableCases"] == 0
    assert metrics["refusalRate"] is None
    assert metrics["falseRefusalRate"] == 0.0


def test_compare_strategies_groups_by_strategy() -> None:
    """对比表就是「同一批题、不同策略」的指标并排。"""
    results = [
        make_case("a", strategy="sparse", retrieved=[1, 9], relevant=[9]),
        make_case("b", strategy="sparse", retrieved=[9], relevant=[9]),
        make_case("a", strategy="hybrid", retrieved=[9], relevant=[9]),
        make_case("b", strategy="hybrid", retrieved=[1, 9], relevant=[9]),
    ]

    table = compare_strategies(results, ks=(1,))

    assert set(table) == {"sparse", "hybrid"}
    assert table["hybrid"]["recall@1"] == 0.5
    assert table["sparse"]["recall@1"] == 0.5
    # 两路都命中的那题在 hybrid 里排第一 → 对比表能看出差异
    assert table["hybrid"]["mrr"] == pytest.approx(0.75, abs=1e-4)
    assert table["sparse"]["mrr"] == pytest.approx(0.75, abs=1e-4)


def test_evaluate_strategy_on_empty_input() -> None:
    assert evaluate_strategy([]) == {"cases": 0}
    assert compare_strategies([]) == {}


def test_case_metrics_skip_recall_for_unanswerable() -> None:
    """无答案题不该出现 recall 键：否则聚合时会把它当 0 拉低均值。"""
    case = make_case("c", retrieved=[1], relevant=[])

    metrics = case.metrics_at(3)

    assert "recall@3" not in metrics
    assert metrics["precision@3"] == 0.0
