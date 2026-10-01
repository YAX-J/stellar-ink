"""图检索的收益判定规则（E5-3 的结论口径）。

这一层要守的是「结论只有一种读法」：

* 基线必须是**非图策略里主指标最好的那一行**（挑弱基线等于自欺）；
* 阈值要**定在噪声之上**（30 题集上，一题就是 3.3 个百分点）；
* 上游失败（限流/超时被记成拒答）与离线 Fake 模型都必须判成 **inconclusive** ——
  这两种情况下的数字不是检索质量的反映，拿它下结论比不下结论更糟。
"""

from __future__ import annotations

from app.rag.eval_service import GRAPH_MARGIN, graph_verdict


def metrics(**values: float) -> dict[str, float]:
    return {key: value for key, value in values.items()}


def test_beneficial_when_graph_beats_the_best_baseline() -> None:
    result = graph_verdict(
        {
            "sparse": metrics(**{"recall@1": 0.83, "ndcg@5": 0.80}),
            "hybrid": metrics(**{"recall@1": 0.86, "ndcg@5": 0.84}),
            "graph_local": metrics(**{"recall@1": 0.93, "ndcg@5": 0.90}),
        },
        ["graph_local"],
        trustworthy=True,
    )

    assert result["verdict"] == "beneficial"
    assert result["baseline"] == "hybrid", "基线取最强的那一行，不是随便挑"
    assert result["delta"] == 0.07
    assert "保留" in result["reason"] or "收益成立" in result["reason"]


def test_no_benefit_tells_you_to_delete_it() -> None:
    """没证明收益时的处置是**删掉**（这是 fast-track-plan 的约定，不是可选项）。"""
    result = graph_verdict(
        {
            "hybrid": metrics(**{"recall@1": 0.86, "ndcg@5": 0.84}),
            "graph_local": metrics(**{"recall@1": 0.87, "ndcg@5": 0.85}),
        },
        ["graph_local"],
        trustworthy=True,
    )

    assert result["verdict"] == "no-benefit"
    assert "删掉" in result["reason"], "结论要写成动作，而不是「略低于基线」这种描述"
    assert result["delta"] == 0.01


def test_tiny_lead_inside_the_margin_is_not_a_win() -> None:
    """刚好超过 0 但没到阈值 → 不算收益（一题的差别就是噪声）。"""
    result = graph_verdict(
        {
            "hybrid": metrics(**{"recall@1": 0.8667}),
            "graph_local": metrics(**{"recall@1": 0.87}),
        },
        ["graph_local"],
        trustworthy=True,
    )

    assert result["delta"] == 0.0033
    assert result["verdict"] == "no-benefit", f"阈值是 {GRAPH_MARGIN}，差 0.003 不算赢"


def test_upstream_errors_make_it_inconclusive() -> None:
    """有行含上游失败时**不下结论**：那种行看起来像「这个策略全错」，其实与质量无关。"""
    result = graph_verdict(
        {
            "hybrid+rerank": {"recall@1": 0.0, "errorCount": 12},
            "graph_local": metrics(**{"recall@1": 0.9}),
        },
        ["graph_local"],
        trustworthy=True,
    )

    assert result["verdict"] == "inconclusive"
    assert "上游失败" in result["reason"]
    assert "重跑" in result["reason"]


def test_fake_models_make_it_inconclusive() -> None:
    """离线 Fake 的数字不能用来判定收益（其它策略那一刻是伪向量）。"""
    result = graph_verdict(
        {
            "dense": metrics(**{"recall@1": 0.1}),
            "graph_local": metrics(**{"recall@1": 0.9}),
        },
        ["graph_local"],
        trustworthy=False,
    )

    assert result["verdict"] == "inconclusive"
    assert "Fake" in result["reason"]


def test_without_graph_arm_it_says_so() -> None:
    result = graph_verdict({"sparse": metrics(**{"recall@1": 0.83})}, [], trustworthy=True)

    assert result["verdict"] == "inconclusive"
    assert "没有图检索策略" in result["reason"]


def test_without_baseline_it_says_so() -> None:
    """只有图检索一行时没有可比对象 —— 如实说，而不是「图检索第一」这种废话结论。"""
    result = graph_verdict(
        {"graph_local": metrics(**{"recall@1": 0.9})}, ["graph_local"], trustworthy=True
    )

    assert result["verdict"] == "inconclusive"
    assert "没有可比对象" in result["reason"]
