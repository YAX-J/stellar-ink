"""`minDenseScore` 标定的**纯逻辑**部分：阈值扫描与推荐点。

这一层刻意与「真的去打嵌入模型」分开：打分逻辑可以离线测，
而一旦把两者混在一起，就只能等额度可用时才敢改一行 —— 那正是这个工具迟迟没做出来的原因。

要守的两条：
1. **单组数据不给结论**：只有有答案题（或只有无答案题）时，任何阈值都能拿满分，
   推荐出来的数字是假的。
2. **并列时偏向拒答**：宁可少答，也不要「答得多但答错」—— 后者在产品里最难被发现。
"""

from __future__ import annotations

from tests.test_scripts import load_script

#: 脚本按文件路径加载：它 `from console import ...`（那只有直接运行时才在 sys.path 上），
#: 这也是 tests/test_scripts.py 对**所有** scripts/*.py 的既定做法
calibrate = load_script("calibrate_dense_score")
ScoreRow = calibrate.ScoreRow
sweep = calibrate.sweep
recommend = calibrate.recommend


def _row(case_id: str, top1: float, *, answerable: bool, hit: bool = False) -> ScoreRow:
    return ScoreRow(case_id=case_id, answerable=answerable, top1=top1, hit_at_k=hit)


def test_sweep_counts_both_rates() -> None:
    rows = [
        _row("a1", 0.80, answerable=True, hit=True),
        _row("a2", 0.40, answerable=True, hit=True),
        _row("a3", 0.35, answerable=True, hit=False),  # 分数不低但没命中：硬答就是答错
        _row("u1", 0.30, answerable=False),
        _row("u2", 0.10, answerable=False),
    ]

    reports = {round(report.threshold, 2): report for report in sweep(rows, [0.0, 0.35, 0.5])}

    assert reports[0.0].answerable_kept == 2 / 3
    assert reports[0.0].unanswerable_refused == 0.0, "阈值 0 谁都挡不住"
    assert reports[0.35].answerable_kept == 2 / 3, "a3 掉出去是因为它本来就没命中，不是因为分数"
    assert reports[0.35].unanswerable_refused == 1.0
    assert reports[0.5].answerable_kept == 1 / 3
    assert reports[0.5].unanswerable_refused == 1.0


def test_wrong_top_hit_does_not_count_as_kept() -> None:
    """分数过线但没命中期望文章 —— 只能算答错，不能算「答对」。"""
    rows = [
        _row("a1", 0.9, answerable=True, hit=False),
        _row("u1", 0.1, answerable=False),
    ]

    report = sweep(rows, [0.5])[0]

    assert report.answerable_kept == 0.0


def test_single_group_yields_no_conclusion() -> None:
    only_answerable = [_row("a1", 0.9, answerable=True, hit=True)]
    only_unanswerable = [_row("u1", 0.9, answerable=False)]

    assert sweep(only_answerable, [0.5]) == []
    assert sweep(only_unanswerable, [0.5]) == []
    assert recommend([]) is None


def test_recommend_prefers_the_best_sum() -> None:
    rows = [
        _row("a1", 0.90, answerable=True, hit=True),
        _row("a2", 0.80, answerable=True, hit=True),
        _row("a3", 0.40, answerable=True, hit=True),
        _row("u1", 0.35, answerable=False),
        _row("u2", 0.10, answerable=False),
    ]

    reports = {report.threshold: report for report in sweep(rows, [0.0, 0.3, 0.5, 0.85])}

    # 0.0 → 1.0 + 0.0；0.3 → 1.0 + 0.5；0.5 → 0.667 + 1.0；0.85 → 0.333 + 1.0
    assert reports[0.5].score > reports[0.3].score
    assert reports[0.5].score > reports[0.85].score

    best = recommend(list(reports.values()))

    assert best is not None and best.threshold == 0.5
    assert round(best.answerable_kept, 3) == round(2 / 3, 3)
    assert best.unanswerable_refused == 1.0


def test_recommend_prefers_refusal_on_ties() -> None:
    """两率之和并列时取拒答率更高的那个：宁可少答，不要「答得多但答错」。"""
    rows = [
        _row("a1", 0.90, answerable=True, hit=True),
        _row("a2", 0.50, answerable=True, hit=True),
        _row("u1", 0.60, answerable=False),
        _row("u2", 0.30, answerable=False),
    ]

    reports = sweep(rows, [0.2, 0.55])

    assert reports[0].score == reports[1].score, "前提：两者之和确实并列"
    assert reports[0].unanswerable_refused < reports[1].unanswerable_refused

    best = recommend(reports)

    assert best is not None
    assert best.threshold == 0.55
    assert best.unanswerable_refused == 0.5
