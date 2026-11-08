"""重复验收不能跨模型/语料合并，也不能套用上一轮语义结论。"""

from copy import deepcopy

import pytest

from tests.test_qa_acceptance_report import reviewed, rows
from tests.test_scripts import load_script

stability = load_script("eval_qa_stability")


def run():
    return {
        "dataset": "holdout_v1",
        "corpusFingerprint": "corpus-v1",
        "modelSignatures": ["chat-v1", "embedding-v1"],
        "rows": rows(),
    }


def test_unreviewed_semantics_stay_unknown_and_refusal_variance_is_visible():
    first, second = run(), run()
    second["rows"][0]["answer"].update(doneReason="refused", evidenceSufficient=False)
    result = stability.summarize_repeats([first, second])
    assert result["metrics"]["answerAccuracy"] is None
    assert result["metrics"]["incompleteAnswerRate"] is None
    assert result["metrics"]["unsupportedAnswerRate"] is None
    assert result["metrics"]["falseRefusalRate"]["mean"] == 0.5
    assert result["metrics"]["falseRefusalRate"]["stdev"] == 0.5
    assert result["stateChangedCases"] == 1


def test_completeness_and_grounding_need_each_rounds_hash_bound_review():
    first, second = run(), run()
    second["rows"][0]["answer"]["answer"] = "另一种不完整回答。[1]"
    reviews = [reviewed(first["rows"]), reviewed(second["rows"])]
    reviews[0]["cases"][0]["complete"] = True
    reviews[1]["cases"][0].update(complete=False, correct=False, citationsSupported=False)
    result = stability.summarize_repeats([first, second], reviews)
    assert result["metrics"]["incompleteAnswerRate"]["mean"] == 0.5
    assert result["metrics"]["unsupportedAnswerRate"]["mean"] == 0.5
    with pytest.raises(ValueError, match="复核已失效"):
        stability.summarize_repeats([first, second], [reviews[0], reviews[0]])


@pytest.mark.parametrize("key", ["dataset", "corpusFingerprint", "modelSignatures"])
def test_mixed_configuration_is_not_called_variance(key):
    first, second = run(), run()
    second[key] = "changed"
    with pytest.raises(ValueError, match="不一致"):
        stability.summarize_repeats([first, second])


def test_incomplete_runs_and_duplicate_cases_are_rejected():
    first, second = run(), run()
    second["rows"] = second["rows"][:1]
    with pytest.raises(ValueError, match="不一致"):
        stability.summarize_repeats([first, second])

    second = deepcopy(first)
    second["rows"].append(deepcopy(second["rows"][0]))
    with pytest.raises(ValueError, match="不一致"):
        stability.summarize_repeats([first, second])


def test_false_refusal_is_not_counted_again_as_a_partial_answer():
    first, second = run(), run()
    for result in (first, second):
        result["rows"][0]["answer"].update(doneReason="refused", evidenceSufficient=False)
    reviews = [reviewed(result["rows"]) for result in (first, second)]
    for review in reviews:
        review["cases"][0].update(correct=False, complete=False)
    result = stability.summarize_repeats([first, second], reviews)
    assert result["metrics"]["falseRefusalRate"]["mean"] == 1
    assert result["metrics"]["incompleteAnswerRate"] is None
