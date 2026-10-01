"""`minDenseScore` 标定的**纯逻辑**部分：阈值扫描与推荐点。

这一层刻意与「真的去打嵌入模型」分开：打分逻辑可以离线测，
而一旦把两者混在一起，就只能等额度可用时才敢改一行 —— 那正是这个工具迟迟没做出来的原因。

要守的三条：
1. **单组数据不给结论**：只有有答案题（或只有无答案题）时，任何阈值都能拿满分，
   推荐出来的数字是假的。
2. **推荐点必须真的能答**（至少保住 90% 的本可答题）：早先的目标函数是
   「保住率 + 拒答率之和」，而**全拒**能拿满分 —— 实测在一组「无答案题分数比有答案题还高」
   的数据上，它推荐了 `minDenseScore = 0.55` 且保住率 **0.0**，照着配会把向量通路静默清空。
   现在的规则是：先满足保住率约束，再在其中挑拒答率最高的。
3. **并列时偏向拒答**：宁可少答，也不要「答得多但答错」—— 后者在产品里最难被发现。
"""

from __future__ import annotations

from tests.test_scripts import load_script

#: 脚本按文件路径加载：它 `from console import ...`（那只有直接运行时才在 sys.path 上），
#: 这也是 tests/test_scripts.py 对**所有** scripts/*.py 的既定做法
calibrate = load_script("calibrate_dense_score")
ScoreRow = calibrate.ScoreRow
sweep = calibrate.sweep
recommend = calibrate.recommend
format_no_threshold = calibrate.format_no_threshold


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


def test_recommend_picks_highest_refusal_among_feasible() -> None:
    """可行阈值之间：取挡住无答案题更多的那个。

    ⚠️ 可行性按「至少保住 90% 的可答题」算，所以两个候选**都得先保住全部可答题**才算可行
    （4 道可答题时 3/4 = 75% 已经不达标了 —— 第一版正是把 0.55 那行算成可行才红的）。
    """
    rows = [
        _row("a1", 0.90, answerable=True, hit=True),
        _row("a2", 0.60, answerable=True, hit=True),
        _row("u1", 0.60, answerable=False),
        _row("u2", 0.30, answerable=False),
    ]

    reports = {round(report.threshold, 2): report for report in sweep(rows, [0.30, 0.55])}

    assert reports[0.30].answerable_kept == 1.0
    assert reports[0.55].answerable_kept == 1.0, "a2 是 0.60，阈值 0.55 不会把它挡掉"
    assert reports[0.30].unanswerable_refused == 0.0
    assert reports[0.55].unanswerable_refused == 0.5

    best = recommend(list(reports.values()))

    assert best is not None and best.threshold == 0.55
    assert best.answerable_kept == 1.0, "选中的门限必须仍然答得出来"


def test_never_recommends_a_threshold_that_answers_nothing() -> None:
    """**回归用例**：分布不可分时不许推荐「全拒」门限。

    实测过的真实形态：无答案题的分数比有答案题还高（同主题但语料里没答案）。
    旧目标函数（保住 + 拒答之和）在这组数据上给 0.55 分最高：
    保住 0.0 + 拒答 1.0 = 1.0 —— 拿它去配，向量通路会被整个关掉，
    症状是「开了混合检索和没开一样」，而没人会怀疑一个「标定出来的数字」。
    """
    rows = [_row(f"a{i}", 0.30, answerable=True, hit=(i < 8)) for i in range(20)] + [
        _row(f"u{i}", 0.50, answerable=False) for i in range(10)
    ]

    reports = sweep(rows, [0.0, 0.31, 0.55])

    assert any(report.threshold == 0.55 and report.answerable_kept == 0.0 for report in reports), (
        "前提：0.55 这一行的保住率确实是 0"
    )
    assert recommend(reports) is None, "无可行阈值时必须返回 None，而不是那个「全拒」的门限"

    text = format_no_threshold(rows)
    assert "标不出可用的 minDenseScore" in text
    assert "不要把「全拒」当成结论" in text, "说明里要直接点出这个陷阱，否则下一个人还会照着配"


def test_recommend_respects_the_keep_constraint() -> None:
    """保住率不足的阈值**不可选**，哪怕它挡住了更多无答案题。"""
    rows = [
        _row("a1", 0.90, answerable=True, hit=True),
        _row("a2", 0.80, answerable=True, hit=True),
        _row("a3", 0.70, answerable=True, hit=True),
        _row("a4", 0.60, answerable=True, hit=True),
        _row("u1", 0.50, answerable=False),
        _row("u2", 0.40, answerable=False),
        _row("u3", 0.30, answerable=False),
    ]

    reports = {round(report.threshold, 2): report for report in sweep(rows, [0.0, 0.55, 0.75])}

    # 0.75 挡住全部无答案题（拒答 1.0），但只保住 2/4 = 50% 的可答题 → 不可选
    assert reports[0.75].unanswerable_refused == 1.0
    assert reports[0.75].answerable_kept == 0.5

    best = recommend(list(reports.values()))

    assert best is not None
    assert best.threshold == 0.55, "选保住 100% 可答题、且挡住全部无答案题的那行"
    assert best.answerable_kept == 1.0
    assert best.unanswerable_refused == 1.0, "0.50/0.40/0.30 都低于 0.55，所以全被挡住"
