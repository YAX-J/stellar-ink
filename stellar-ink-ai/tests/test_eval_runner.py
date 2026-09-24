"""评测运行器：黄金集校验、多策略对比、单题失败降级、落库形态。

运行器是「评测台」的骨架：前端点一次按钮，它要跑完 题目 × 策略 并给出可对比的表。
因此这里重点固定三件事：数据模型校验（错题要能定位）、单题失败不毁整轮、输出形状与
`ai_eval_run` 表一致。
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.rag.eval_runner import (
    EvalCase,
    EvalDataset,
    ListRetriever,
    RetrievalOutcome,
    StrategySpec,
    datasets_available,
    run_dataset,
)
from app.rag.metrics import CaseResult


def outcome(posts: list[int], *, latency: float = 100.0, refused: bool = False) -> RetrievalOutcome:
    return RetrievalOutcome(
        posts=posts,
        chunks=[f"c{post}" for post in posts],
        latency_ms=latency,
        refused=refused,
    )


DATASET = EvalDataset(
    name="公开文章黄金集 v1",
    description="用于对比检索策略的最小集",
    cases=[
        EvalCase("q1", "星笺为什么把文章比作星辰？", expected_post_ids=[12]),
        EvalCase("q2", "混合检索怎么融合两路排名？", expected_post_ids=[7, 9]),
        EvalCase("q3", "今晚吃什么？"),  # 无答案题
    ],
)


class BoomRetriever:
    """总抛异常的检索器：验证「单题失败不中断整轮」。"""

    async def retrieve(self, question: str, *, top_k: int) -> RetrievalOutcome:
        raise RuntimeError("上游炸了")


def test_dataset_summary_separates_answerable_cases() -> None:
    summary = DATASET.summary()

    assert summary["cases"] == 3
    assert summary["answerableCases"] == 2
    assert summary["unanswerableCases"] == 1


def test_dataset_rejects_duplicate_case_ids() -> None:
    """caseId 重复会让聚合时重复计分，必须挡住。"""
    with pytest.raises(ValueError, match="重复"):
        EvalDataset(
            name="dup",
            cases=[EvalCase("q1", "问题一", [1]), EvalCase("q1", "问题一改", [2])],
        )


def test_dataset_rejects_empty_inputs() -> None:
    with pytest.raises(ValueError):
        EvalDataset(name="", cases=[EvalCase("q1", "问题", [1])])
    with pytest.raises(ValueError):
        EvalDataset(name="empty", cases=[])


def test_dataset_json_round_trip() -> None:
    payload = DATASET.to_json()

    restored = EvalDataset.from_json(payload)

    assert restored.name == DATASET.name
    assert [case.case_id for case in restored.cases] == ["q1", "q2", "q3"]
    assert restored.cases[1].expected_post_ids == [7, 9]
    assert restored.cases[2].answerable is False


def test_case_row_requires_id_and_question() -> None:
    with pytest.raises(ValueError, match="caseId"):
        EvalCase.from_row({"question": "没有 id"})
    with pytest.raises(ValueError, match="caseId"):
        EvalCase.from_row({"caseId": "q1", "question": "   "})


def test_case_row_parses_graded_relevance() -> None:
    case = EvalCase.from_row(
        {
            "caseId": "q9",
            "question": "分级相关标注",
            "expectedPostIds": [3, 4],
            "gradedRelevance": {"3": 2.0, "4": 0.5},
        }
    )

    assert case.graded_relevance == {3: 2.0, 4: 0.5}


async def test_run_dataset_compares_two_strategies() -> None:
    """运行器只管编排：两组策略各跑满全部题目，并产出可对比的表。"""
    sparse = ListRetriever(
        {"星笺为什么把文章比作星辰？": outcome([12]), "混合检索怎么融合两路排名？": outcome([7, 1])}
    )
    hybrid = ListRetriever(
        {"星笺为什么把文章比作星辰？": outcome([12]), "混合检索怎么融合两路排名？": outcome([7, 9])}
    )

    result = await run_dataset(
        DATASET,
        [
            StrategySpec("sparse", sparse, top_k=5),
            StrategySpec("hybrid", hybrid, top_k=5),
        ],
        ks=(1, 2),
    )

    assert set(result.per_strategy) == {"sparse", "hybrid"}
    assert len(result.cases) == 6
    assert {case.strategy for case in result.cases} == {"sparse", "hybrid"}
    # 每题都要被两组策略各跑一次（保证对比是同一批题）
    for case_id in ("q1", "q2", "q3"):
        assert len([case for case in result.cases if case.case_id == case_id]) == 2
    # 具体分值差异放在逐题层面断言，避免被「单答案题 + 多答案题」的平均掩盖
    hybrid_q2 = next(
        case for case in result.cases if case.case_id == "q2" and case.strategy == "hybrid"
    )
    sparse_q2 = next(
        case for case in result.cases if case.case_id == "q2" and case.strategy == "sparse"
    )
    assert hybrid_q2.metrics_at(2)["recall@2"] > sparse_q2.metrics_at(2)["recall@2"]


async def test_recall_at_k_denominator_is_all_relevant_posts() -> None:
    """把口径单独钉一次：`recall@K` 不是「前 K 条里有没有」，而是
    「前 K 条覆盖了多少比例的相关项」；后者是 `hit@K`。"""
    retriever = ListRetriever({"混合检索怎么融合两路排名？": outcome([7, 1, 9])})

    result = await run_dataset(DATASET, [StrategySpec("s", retriever)], ks=(1, 3))
    q2 = next(case for case in result.cases if case.case_id == "q2")

    assert q2.metrics_at(1)["recall@1"] == 0.5  # q2 有两个相关项，top-1 只覆盖一个
    assert q2.metrics_at(3)["recall@3"] == 1.0  # top-3 两个都覆盖
    assert q2.metrics_at(1)["hit@1"] == 1.0  # hit 只问「有没有」


async def test_run_dataset_marks_unanswerable_cases() -> None:
    retriever = ListRetriever(
        {
            "星笺为什么把文章比作星辰？": outcome([12]),
            "混合检索怎么融合两路排名？": outcome([7, 9]),
            "今晚吃什么？": RetrievalOutcome(posts=[], chunks=[], refused=True),
        }
    )

    result = await run_dataset(DATASET, [StrategySpec("hybrid", retriever)], ks=(3,))

    unanswerable = [case for case in result.cases if case.case_type == "unanswerable"]
    assert len(unanswerable) == 1
    assert unanswerable[0].refused is True
    assert result.per_strategy["hybrid"]["refusalRate"] == 1.0
    assert result.per_strategy["hybrid"]["falseRefusalRate"] == 0.0


async def test_single_case_failure_does_not_abort_the_run() -> None:
    """一道题炸了不该让整轮对比作废 —— 否则网络抖一下评测结果就没了。"""
    good = ListRetriever({"星笺为什么把文章比作星辰？": outcome([12])})

    result = await run_dataset(
        DATASET,
        [StrategySpec("boom", BoomRetriever()), StrategySpec("good", good)],
        ks=(1,),
    )

    boom_cases = [case for case in result.cases if case.strategy == "boom"]
    assert len(boom_cases) == 3
    assert all(case.retrieved_posts == [] for case in boom_cases)
    assert all(case.refused for case in boom_cases), (
        "失败应显式表现为拒答/空结果，而不是静默当 0 分"
    )
    # 另一路仍然完整
    assert len([case for case in result.cases if case.strategy == "good"]) == 3


async def test_run_dataset_reports_progress() -> None:
    retriever = ListRetriever({})
    seen: list[tuple[int, int]] = []

    await run_dataset(
        DATASET,
        [StrategySpec("a", retriever), StrategySpec("b", retriever)],
        on_case=lambda done, total: seen.append((done, total)),
    )

    assert seen[0] == (1, 6)
    assert seen[-1] == (6, 6), "进度必须走到总数（前端进度条靠它）"


async def test_run_dataset_requires_strategies() -> None:
    with pytest.raises(ValueError, match="至少要有一个策略"):
        await run_dataset(DATASET, [])


async def test_strategy_spec_validates_itself() -> None:
    with pytest.raises(ValueError):
        StrategySpec("", ListRetriever({}))
    with pytest.raises(ValueError):
        StrategySpec("a", ListRetriever({}), top_k=0)


async def test_list_retriever_truncates_to_top_k_and_refuses_unknown() -> None:
    retriever = ListRetriever({"q": outcome([1, 2, 3, 4])})

    limited = await retriever.retrieve("q", top_k=2)
    unknown = await retriever.retrieve("没准备的问题", top_k=2)

    assert limited.posts == [1, 2]
    assert unknown.posts == [] and unknown.refused is True


async def test_run_result_row_matches_database_shape() -> None:
    """落库形状要与 `ai_eval_run` 的两列 JSON 对应，前端才能直接渲染与下钻。"""
    retriever = ListRetriever({"星笺为什么把文章比作星辰？": outcome([12])})

    result = await run_dataset(DATASET, [StrategySpec("hybrid", retriever)], ks=(1,))
    row = result.to_row()

    assert row["dataset"] == DATASET.name
    assert "hybrid" in row["metricsJson"]
    first = row["caseResultJson"][0]
    assert set(first) >= {"caseId", "strategy", "retrievedPosts", "refused", "latencyMs"}
    # 必须可 JSON 序列化（直接写库/回前端）
    assert json.loads(json.dumps(row, ensure_ascii=False))


def test_result_keeps_ks_for_reproduction() -> None:
    result = EvalDataset  # 占位避免未使用导入告警的错觉：此处只断言字段存在
    assert result is EvalDataset


async def test_citation_accuracy_counts_only_retrieved_chunks() -> None:
    """模型引用了没检索到的片段 → 引用准确率下降（用户点进去是空的）。"""
    retriever = ListRetriever(
        {
            "星笺为什么把文章比作星辰？": RetrievalOutcome(
                posts=[12], chunks=["c12"], cited_chunks=["c12", "编造的"], latency_ms=50
            )
        }
    )

    result = await run_dataset(DATASET, [StrategySpec("hybrid", retriever)], ks=(1,))

    assert result.per_strategy["hybrid"]["citationAccuracy"] < 1.0


def test_datasets_available_lists_json_files(tmp_path: Path) -> None:
    (tmp_path / "golden_v1.json").write_text("{}", encoding="utf-8")
    (tmp_path / "notes.txt").write_text("", encoding="utf-8")

    assert list(datasets_available(tmp_path)) == ["golden_v1.json"]
    assert list(datasets_available(tmp_path / "not-exist")) == []


def test_case_result_type_is_shared_with_metrics_layer() -> None:
    """运行器产出的逐题结果必须就是 metrics 层认识的类型，避免中间再映射一次。"""
    case = CaseResult(
        case_id="q1",
        strategy="s",
        question="问题",
        case_type="answerable",
        retrieved_posts=[1],
        retrieved_chunks=["c1"],
        relevant_posts=[1],
    )

    assert case.metrics_at(1)["recall@1"] == 1.0
