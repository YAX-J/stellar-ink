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
    EvalRunRequest,
    EvalRunResponse,
    IndexJob,
    IndexRebuildRequest,
    ProviderModelsRequest,
    ProviderModelsResult,
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
    ("eval_run_request.json", EvalRunRequest),
    ("eval_run_response.json", EvalRunResponse),
    # 模型清单（面板「添加模型」）：两边读同一份，字段是 provider/baseUrl/apiKey/role
    ("provider_models_request.json", ProviderModelsRequest),
    ("provider_models_result.json", ProviderModelsResult),
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


def test_provider_models_response_has_no_key_field() -> None:
    """模型清单的响应里**没有**任何密钥字段 —— 连掩码都没有（红线 §5 的第一条）。

    这条断言的价值在于：它是**契约层**的。哪天真有人「顺手加个 apiKeyMask 方便用户核对」，
    这里会立刻红，而不是等到某次日志/审计里出现密钥才发现。
    """
    payload = load_fixture("provider_models_result.json")
    serialized = ProviderModelsResult.model_validate(payload).model_dump(
        by_alias=True, mode="json"
    )

    assert set(serialized) == {"models", "truncated", "source"}
    for entry in serialized["models"]:
        assert set(entry) == {"id", "created"}, "条目只留 id（外加可选的 created）"
    assert "created" in serialized["models"][-1] and serialized["models"][-1]["created"] is None, (
        "样例里必须有「供应商没给 created」的形态：可空字段得有人守着"
    )


def test_provider_models_request_key_is_optional() -> None:
    """`apiKey` 可留空（= 用该用户已保存的该角色密钥），`role` 缺省是 chat。"""
    request = ProviderModelsRequest.model_validate(
        {"provider": "openai_compatible", "baseUrl": "https://api.example.com/v1"}
    )

    assert request.api_key is None
    assert request.role == "chat"


def test_provider_models_request_accepts_a_null_base_url_for_fake() -> None:
    """`baseUrl` 必须能接受 **null**：Java 侧把「地址栏是空的」归一成 null 再转发。

    契约层只收字符串的话，面板选 fake 时真实链路会以 422 结束，而两侧单测各自都是绿的。
    """
    request = ProviderModelsRequest.model_validate({"provider": "fake", "baseUrl": None})

    assert request.base_url is None
    assert request.provider == "fake"
