"""最终答案验收不能把结构检查冒充语义正确，也不能复用上一轮的复核。"""

from copy import deepcopy

import pytest

from tests.test_scripts import load_script

runner = load_script("eval_qa_answers")


def rows():
    return [
        {"caseId": "a", "question": "怎么做？", "expectedPostIds": [1], "status": 200,
         "code": 0, "elapsedMs": 20, "answer": {"answer": "用文件。[1]", "doneReason": "stop",
         "evidenceSufficient": True, "citations": [{}], "usage": {"totalTokens": 40}},
         "structure": {"checked": 1, "problems": []}},
        {"caseId": "u", "question": "未知细节？", "expectedPostIds": [], "status": 200,
         "code": 0, "elapsedMs": 10, "answer": {"answer": "没有依据", "doneReason": "refused",
         "evidenceSufficient": False, "citations": [], "usage": {"totalTokens": 0}},
         "structure": {"checked": 0, "problems": []}},
    ]


def reviewed(source):
    return {"cases": [
        {"caseId": r["caseId"], "answerHash": runner.answer_hash(r), "correct": True,
         "citationsSupported": True} for r in source
    ]}


def test_structure_is_not_semantic_verification() -> None:
    summary = runner.summarize(rows())
    assert summary["literalCitationRate"] == 1
    assert summary["answerAccuracy"] is None
    assert summary["citationSupportRateByAnswer"] is None
    assert summary["reviewedCases"] == 0
    assert summary["unanswerableRefusalRate"] == 1


def test_fully_reviewed_answers_can_be_counted() -> None:
    source = rows()
    summary = runner.summarize(source, reviewed(source))
    assert summary["answerAccuracy"] == summary["citationSupportRateByAnswer"] == 1
    assert summary["totalTokens"] == 40


def test_review_is_invalid_after_answer_or_citation_changes() -> None:
    source = rows()
    review = reviewed(source)
    changed = deepcopy(source)
    changed[0]["answer"]["citations"] = [{"snippet": "另一份证据"}]
    with pytest.raises(ValueError, match="复核已失效"):
        runner.summarize(changed, review)


def test_partial_or_duplicate_review_cannot_claim_complete_validation() -> None:
    source = rows()
    review = reviewed(source)
    with pytest.raises(ValueError, match="完整覆盖"):
        runner.summarize(source, {"cases": review["cases"][:1]})
    with pytest.raises(ValueError, match="不能重复"):
        runner.summarize(source, {"cases": review["cases"] * 2})


def test_unknown_source_is_not_counted_as_a_valid_literal_citation() -> None:
    source = rows()
    source[0]["structure"] = {"checked": 0, "problems": [{"kind": "unknownChunk"}]}
    summary = runner.summarize(source)
    assert summary["literalCitationRate"] == 0
    source[0]["code"] = 500
    source[0]["elapsedMs"] = 60000
    summary = runner.summarize(source)
    assert summary["errors"] == 1
    assert summary["latencyP50Ms"] == 10


def test_fabricated_answer_to_negative_case_still_counts_in_citation_support() -> None:
    source = rows()
    source[1]["answer"].update(
        answer="我猜是这个名字。[1]", doneReason="stop", evidenceSufficient=True,
    )
    review = reviewed(source)
    review["cases"][1].update(correct=False, citationsSupported=False)
    summary = runner.summarize(source, review)
    assert summary["factAnswerCases"] == 2
    assert summary["citationSupportRateByAnswer"] == .5
    assert summary["answerAccuracy"] == .5
