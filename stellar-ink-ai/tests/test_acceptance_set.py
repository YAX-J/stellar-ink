"""独立验收题必须与标定题分开，且每道可答题都能找到标注原文。"""

import re

import pytest

from app.rag.eval_runner import EvalDataset
from app.rag.eval_service import default_dataset_path
from app.rag.seed_corpus import load_seed_posts
from tests.test_scripts import load_script


def test_acceptance_is_disjoint_and_evidence_is_real() -> None:
    training = EvalDataset.load(default_dataset_path("golden_v1"))
    acceptance = EvalDataset.load(default_dataset_path("acceptance_v1"))
    assert len(acceptance.cases) == 24
    assert acceptance.answerable_count == acceptance.unanswerable_count == 12
    assert not ({c.question for c in training.cases} & {c.question for c in acceptance.cases})
    posts = {post.post_id: post for post in load_seed_posts()}
    for case in acceptance.cases:
        if not case.answerable:
            assert case.expected_answer is None
            continue
        anchors = re.split(r"[；，。]", (case.expected_answer or "").replace("`", ""))
        assert all(
            any(
                anchor.strip() in posts[post_id].plain.replace("`", "")
                for anchor in anchors
                if len(anchor.strip()) >= 4
            )
            for post_id in case.expected_post_ids
        ), case.case_id


def test_acceptance_cannot_be_used_to_fit_thresholds() -> None:
    script = load_script("calibrate_hybrid_score")
    with pytest.raises(ValueError, match="不能用于标定"):
        script.load_calibration_dataset("acceptance_v1")


def test_holdout_is_separate_and_cannot_fit_thresholds() -> None:
    import json

    holdout = EvalDataset.load(default_dataset_path("holdout_v1"))
    previous = {
        c.question
        for name in ("golden_v1", "acceptance_v1")
        for c in EvalDataset.load(default_dataset_path(name)).cases
    }
    assert len(holdout.cases) == 16
    assert holdout.answerable_count == 8
    assert not previous & {c.question for c in holdout.cases}
    assert (
        json.loads(default_dataset_path("holdout_v1").read_text(encoding="utf-8"))["purpose"]
        == "holdout"
    )
    posts = {p.post_id: p.plain.replace("`", "") for p in load_seed_posts()}
    for case in holdout.cases:
        if not case.answerable:
            assert case.expected_answer is None
            continue
        for clause in re.split(r"[，。；]", case.expected_answer or ""):
            if len(clause.strip()) >= 4:
                assert any(clause.strip() in posts[i] for i in case.expected_post_ids), case.case_id
    with pytest.raises(ValueError, match="不能用于标定"):
        load_script("calibrate_hybrid_score").load_calibration_dataset("holdout_v1")
