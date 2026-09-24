"""契约测试：所有模型都必须与 ``tests/fixtures/*.json`` 双向一致。

这一组用例是 M0-4（Java 契约测试）的对侧：Java 读同一批 JSON，
所以任何一侧改了字段名或嵌套结构，两侧测试会同时失败，而不是等到联调才发现。

断言方式：``fixture JSON → 模型 → model_dump(by_alias=True)`` 必须与原文**逐键相等**，
既证明能解析，也证明序列化回驼峰键，不出现蛇形字段泄漏到边界。
"""

import json
from pathlib import Path
from typing import Any

import pytest
from pydantic import BaseModel, ValidationError

from app.schemas import (
    ErrorBody,
    IndexJob,
    IndexRebuildRequest,
    QaAnswer,
    QaStreamRequest,
    WritingSuggestRequest,
    WritingSuggestResult,
)

FIXTURES = Path(__file__).parent / "fixtures"


def load_fixture(name: str) -> dict[str, Any]:
    with (FIXTURES / name).open(encoding="utf-8") as handle:
        data: dict[str, Any] = json.load(handle)
    return data


ROUND_TRIP_CASES: list[tuple[str, type[BaseModel]]] = [
    ("qa_stream_request.json", QaStreamRequest),
    ("qa_answer.json", QaAnswer),
    ("writing_suggest_request.json", WritingSuggestRequest),
    ("writing_suggest_result.json", WritingSuggestResult),
    ("index_rebuild_request.json", IndexRebuildRequest),
    ("index_job.json", IndexJob),
    ("error_body.json", ErrorBody),
]


@pytest.mark.parametrize(("fixture_name", "model"), ROUND_TRIP_CASES)
def test_fixture_round_trips_through_model(fixture_name: str, model: type[BaseModel]) -> None:
    payload = load_fixture(fixture_name)

    parsed = model.model_validate(payload)
    serialized = parsed.model_dump(by_alias=True, exclude_none=False, mode="json")

    assert serialized == payload, f"{fixture_name} 与 {model.__name__} 的契约已经不一致"


@pytest.mark.parametrize(("fixture_name", "model"), ROUND_TRIP_CASES)
def test_no_snake_case_keys_leak_to_boundary(fixture_name: str, model: type[BaseModel]) -> None:
    payload = load_fixture(fixture_name)

    serialized = model.model_validate(payload).model_dump(by_alias=True, mode="json")

    for key, value in serialized.items():
        assert "_" not in key, f"{fixture_name} 输出了蛇形键 {key}，Java 侧会解析不到"
        if isinstance(value, dict):
            for nested_key in value:
                assert "_" not in nested_key, f"{fixture_name}.{key} 输出了蛇形键 {nested_key}"


def test_extra_field_is_rejected() -> None:
    payload = load_fixture("qa_stream_request.json")
    payload["unexpectedField"] = "不该被静默接收"

    with pytest.raises(ValidationError):
        QaStreamRequest.model_validate(payload)


def test_blank_question_is_rejected() -> None:
    with pytest.raises(ValidationError):
        QaStreamRequest.model_validate({"question": "   "})


def test_oversized_question_is_rejected() -> None:
    with pytest.raises(ValidationError):
        QaStreamRequest.model_validate({"question": "星" * 501})


def test_writing_task_requires_draft() -> None:
    # polish / continue / tags / summary 没有草稿无法工作，属于契约错误
    with pytest.raises(ValidationError):
        WritingSuggestRequest.model_validate({"task": "polish", "draft": "  "})

    # title / outline 允许空草稿（例如只给一个主题词）
    request = WritingSuggestRequest.model_validate({"task": "title"})
    assert request.task.value == "title"


def test_refusal_answer_needs_no_citation() -> None:
    # 证据不足时允许零引用 + refused 结束原因（M3 的拒答路径）
    answer = QaAnswer.model_validate(
        {
            "answer": "文章中没有找到依据。",
            "citations": [],
            "doneReason": "refused",
            "evidenceSufficient": False,
        }
    )

    assert answer.done_reason.value == "refused"
    assert answer.citations == []
    assert answer.usage.total_tokens == 0


def test_post_rebuild_requires_post_id_by_contract() -> None:
    # 契约层允许缺省（由服务端返回明确错误），但单篇重建必须能表达 postId
    request = IndexRebuildRequest.model_validate({"kind": "post_rebuild", "postId": 12})

    assert request.post_id == 12
    assert request.model_dump(by_alias=True)["postId"] == 12
