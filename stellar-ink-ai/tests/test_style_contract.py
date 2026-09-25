"""写作画像的跨语言契约测试：fixture 必须与当前实现一致。

fixture 由 `scripts/gen_style_fixture.py` 用**固定样本**生成（不读种子语料），
Java 侧读同一份文件做形状断言。这里守的是另一半：
「fixture 里的数字确实来自现在的算法」—— 少了这条，改了统计口径后 fixture 就成了摆设，
Java 那边依然全绿，而两边其实已经不一致了。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

from app.schemas.style import WritingStyleResult

FIXTURES = Path(__file__).parent / "fixtures"
SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

from gen_style_fixture import build, camel  # noqa: E402 - 需要先把 scripts 加进 path

PROFILE_FIELDS = {
    "sampleCount",
    "charCount",
    "paragraphCount",
    "sentenceCount",
    "medianSentenceChars",
    "minSentenceChars",
    "maxSentenceChars",
    "shortSentenceRatio",
    "clausesPer100Chars",
    "questionRatio",
    "informalMarkRatio",
    "commonPhrases",
    "transitions",
    "topTags",
}


def load(name: str) -> dict:
    with (FIXTURES / name).open(encoding="utf-8") as handle:
        return json.load(handle)


def test_fixture_matches_the_current_algorithm() -> None:
    """重新算一遍：fixture 与实现必须逐字段相同（改了口径就要重新生成）。

    比较的是**驼峰版本**（fixture 里就是驼峰）：拿蛇形结果直接比会永远不相等，
    而那种失败看起来像「口径变了」，实际上只是自己忘了转换。
    """
    stale = load("writing_style_result.json")

    assert camel(build()) == stale, (
        "fixture 与当前画像实现不一致：口径改了就要跑 "
        "`uv run python scripts/gen_style_fixture.py` 并提交新 fixture"
    )


def test_fixture_request_shape_is_accepted_by_the_contract() -> None:
    from app.schemas.style import WritingStyleRequest

    request = load("writing_style_request.json")

    model = WritingStyleRequest.model_validate(request)
    assert model.author_id == 1
    assert model.max_samples == 20


def test_fixture_profile_is_accepted_by_the_contract() -> None:
    payload = load("writing_style_result.json")

    model = WritingStyleResult.model_validate(payload)

    assert model.author_id == 1
    assert model.evidence_sufficient is True
    assert model.profile is not None
    assert set(model.profile.model_dump(by_alias=True)) == PROFILE_FIELDS


def test_fixture_does_not_contain_author_sentences() -> None:
    """契约向量里同样不允许出现原句：这是画像的不变式，不是某个实现的巧合。"""
    payload = load("writing_style_result.json")
    profile = payload["profile"]

    for phrase in profile["commonPhrases"]:
        assert not any(mark in phrase for mark in "。！？，、；："), phrase
        assert 3 <= len(phrase) <= 6, phrase


@pytest.mark.parametrize("field", ["sampleCount", "charCount", "sentenceCount"])
def test_fixture_counts_are_positive(field: str) -> None:
    profile = load("writing_style_result.json")["profile"]

    assert profile[field] > 0, f"{field} 为正才说明样本真的被量过"


# --------------------------------------------------------------- E2 只读 Agent


def test_agent_fixture_matches_the_current_translation() -> None:
    """Agent fixture 也要与翻译层一致：否则 Java 侧绿着，两侧其实已经漂移。"""
    sys.path.insert(0, str(SCRIPTS))
    from gen_agent_fixture import build  # noqa: PLC0415 - 需要先把 scripts 加进 path

    assert build() == load("agent_ask_result.json"), (
        "fixture 与当前 Agent 翻译不一致：改了契约就要跑 "
        "`uv run python scripts/gen_agent_fixture.py`"
    )


def test_agent_fixture_pins_the_budget_exhausted_shape() -> None:
    """「预算触顶」的形态必须在契约里钉死：空答案 + 有引用 + 有步骤 + 有原因。"""
    payload = load("agent_ask_result.json")

    assert payload["doneReason"] == "length"
    assert payload["answer"] == "", "预算触顶时答案为空是正常形态"
    assert payload["citations"], "没收敛也要带回已经查到的引用"
    assert payload["steps"], "跑了就要留下逐步记录"
    assert any(step["error"] for step in payload["steps"]), "要留下「为什么没收敛」"
    assert payload["interruptedBy"] == "budget"
