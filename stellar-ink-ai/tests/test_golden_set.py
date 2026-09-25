"""黄金集的自检：题目必须对应真实存在的文章，否则评测跑出来的召回率没有意义。

为什么值得单独测：黄金集是最容易出错的一环 —— 写错一个 post id，召回率就会永远偏低，
而没人会怀疑「标注本身错了」。这里把黄金集与种子内容包绑起来校验：
每个 `expectedPostIds` 都必须能在 `02_init-data.sql` 里找到对应文章。
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.rag.eval_runner import EvalDataset
from scripts.check_golden_evidence import labeled_evidence_hits
from scripts.seed_posts import load_seed_posts

GOLDEN = Path(__file__).parent / "fixtures" / "eval" / "golden_v1.json"


@pytest.fixture(scope="module")
def dataset() -> EvalDataset:
    return EvalDataset.load(GOLDEN)


@pytest.fixture(scope="module")
def seed_post_ids() -> set[int]:
    return {post.post_id for post in load_seed_posts()}


def test_dataset_meets_the_roadmap_minimum_size(dataset: EvalDataset) -> None:
    """roadmap 要求「不少于 30 个问题，其中包含无答案问题」。"""
    assert len(dataset.cases) >= 30
    assert dataset.unanswerable_count >= 5, "无答案题太少就测不出拒答行为"
    assert dataset.answerable_count >= 20


def test_every_expected_post_exists_in_seed_content(
    dataset: EvalDataset, seed_post_ids: set[int]
) -> None:
    """标错 post id 会让召回率永久偏低，且很难被发现 —— 这条断言专治它。"""
    for case in dataset.cases:
        missing = [post_id for post_id in case.expected_post_ids if post_id not in seed_post_ids]
        assert not missing, f"{case.case_id} 引用了不存在的文章：{missing}"


def test_questions_are_distinct_and_non_trivial(dataset: EvalDataset) -> None:
    questions = [case.question.strip() for case in dataset.cases]

    assert len(set(questions)) == len(questions), "重复问题会稀释指标"
    assert all(len(question) >= 6 for question in questions), "问题太短不足以评估检索"


def test_answerable_cases_have_expected_answers(dataset: EvalDataset) -> None:
    """有答案题要写参考答案要点：人工核对与将来评 Faithfulness 都要用。"""
    for case in dataset.cases:
        if case.answerable:
            assert case.expected_answer, f"{case.case_id} 缺少 expectedAnswer"


def test_unanswerable_cases_have_no_expected_posts(dataset: EvalDataset) -> None:
    """无答案题的判据就是「没有期望文章」，不能又标文章又要求拒答。"""
    unanswerable = [case for case in dataset.cases if not case.answerable]

    assert unanswerable
    for case in unanswerable:
        assert case.expected_answer is None, f"{case.case_id} 无答案题不该有参考答案"


def test_graded_relevance_uses_known_posts(dataset: EvalDataset) -> None:
    """分级标注里的 post id 也必须真实存在，且要和 expectedPostIds 一致。"""
    graded_cases = [case for case in dataset.cases if case.graded_relevance]

    assert graded_cases, "至少要有一道分级相关题，用来验证 NDCG 的分级增益"
    for case in graded_cases:
        assert case.graded_relevance is not None
        assert set(case.graded_relevance) == set(case.expected_post_ids), (
            f"{case.case_id} 的分级标注与 expectedPostIds 不一致"
        )
        assert all(gain > 0 for gain in case.graded_relevance.values())


def test_every_answerable_case_has_evidence_in_its_labeled_posts(dataset: EvalDataset) -> None:
    """标注自洽性：标了某篇文章，就必须能在**它的段落里**找到答案要点。

    为什么值得测：黄金集最容易犯的错不是「题写错」，而是「标错文章」——
    标错会让召回率长期偏低，而没人会怀疑标注本身。判据（锚点切分 + 只在该文章内部切块）
    与 `scripts/check_golden_evidence.py` 共用一份实现，测试与人工复核不会得出两个结论。
    允许少量漏判（短语可能跨句），因此要求**至少一半**标注文章能找到证据。
    """
    posts = {post.post_id: post for post in load_seed_posts()}
    weak: list[str] = []

    for case in dataset.cases:
        if not case.answerable or not case.expected_answer:
            continue
        hits, total = labeled_evidence_hits(case, posts)
        if hits * 2 < total:
            weak.append(f"{case.case_id}（{hits}/{total} 篇里找到证据）")

    assert not weak, "以下题目在其标注的文章里找不到答案要点，请复核标注：" + "；".join(weak)


def test_json_is_valid_and_self_describing() -> None:
    payload = json.loads(GOLDEN.read_text(encoding="utf-8"))

    assert payload["name"] == "公开文章黄金集 v1"
    assert payload["_comment"], "文件里要写清标注口径，便于他人改题"
    assert all("caseId" in row and "question" in row for row in payload["cases"])
