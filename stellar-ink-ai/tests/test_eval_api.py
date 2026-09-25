"""评测接口测试：形状、口径与错误码。

为什么值得测：这个接口的输出**直接变成前端对比表**。列名错了、拒答率的分子分母混了、
错误请求返回 500 而不是 400，都会让「评测结果」被误读 —— 而误读的代价是拿错结论去调检索策略。
测试全部走真实签名头（`tests/signing.py`），顺带把「评测接口受内部签名保护」这件事钉住。
"""

from __future__ import annotations

import json

import pytest
from fastapi import FastAPI

from app.main import create_app
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    # 固定时钟：签名向量里的时间戳必须落在 ±60s 窗口内
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    from app.core.internal_auth import InternalRequestVerifier

    return create_app(verifier=InternalRequestVerifier(secret))


async def post(app: FastAPI, secret: str, path: str, payload: dict) -> tuple[int, dict]:
    body = json.dumps(payload, ensure_ascii=False)
    headers = {
        **signed_headers("POST", path, secret=secret, body=body, role="ADMIN", user_id=1),
        "Content-Type": "application/json",
    }
    response = await call(app, "POST", path, headers=headers, content=body)
    return response.status_code, response.json()


async def get(app: FastAPI, secret: str, path: str) -> tuple[int, object]:
    headers = signed_headers("GET", path, secret=secret, role="ADMIN", user_id=1)
    response = await call(app, "GET", path, headers=headers)
    return response.status_code, response.json()


async def test_eval_run_needs_signature(app: FastAPI) -> None:
    """评测会消耗算力甚至（将来）模型费用，绝不能匿名触发。"""
    response = await call(app, "POST", "/eval/run", json={})

    assert response.status_code == 401


async def test_default_run_returns_the_standard_comparison_table(app: FastAPI, secret: str) -> None:
    status, payload = await post(app, secret, "/eval/run", {})

    assert status == 200
    assert payload["dataset"] == "公开文章黄金集 v1"
    assert payload["corpusSource"].startswith("seed-sql:")
    assert payload["corpusPosts"] == 29
    assert payload["corpusChunks"] == 41
    assert payload["models"] == "fake"
    keys = [row["key"] for row in payload["strategies"]]
    assert keys == ["sparse", "dense", "hybrid", "hybrid+rerank", "sparse+floor"]
    assert set(payload["perStrategy"]) == set(keys)
    assert payload["perStrategy"]["sparse"]["recall@1"] == pytest.approx(0.8333, abs=1e-3)
    assert payload["perStrategy"]["sparse+floor"]["refusalRate"] == pytest.approx(0.4, abs=1e-3)


async def test_cases_are_returned_for_drill_down(app: FastAPI, secret: str) -> None:
    status, payload = await post(app, secret, "/eval/run", {"strategies": [{"key": "sparse"}]})

    assert status == 200
    cases = payload["cases"]
    assert len(cases) == 30, "逐题明细要覆盖整个数据集"
    assert {row["caseId"] for row in cases} == {f"q{index:03d}" for index in range(1, 31)}
    first = cases[0]
    assert set(first) == {
        "caseId",
        "strategy",
        "question",
        "caseType",
        "retrievedPosts",
        "relevantPosts",
        "refused",
        "latencyMs",
    }
    assert first["strategy"] == "sparse"


async def test_notes_explain_what_fake_models_cannot_prove(app: FastAPI, secret: str) -> None:
    """诚实提示必须随响应一起回去：面板用户不该自己猜 Fake 向量有没有语义。"""
    _, payload = await post(app, secret, "/eval/run", {"strategies": [{"key": "dense"}]})

    joined = "".join(payload["notes"])
    assert "FakeProvider" in joined
    assert "不代表真实语义质量" in joined


async def test_max_cases_limits_the_dataset(app: FastAPI, secret: str) -> None:
    status, payload = await post(app, secret, "/eval/run", {"maxCases": 4})

    assert status == 200
    assert len({row["caseId"] for row in payload["cases"]}) == 4


async def test_custom_strategy_switches_are_honoured(app: FastAPI, secret: str) -> None:
    status, payload = await post(
        app,
        secret,
        "/eval/run",
        {
            "strategies": [
                {"key": "floor-13", "enableSparse": True, "enableDense": False, "minScore": 13.0}
            ]
        },
    )

    assert status == 200
    assert [row["key"] for row in payload["strategies"]] == ["floor-13"]
    assert "minScore=13.0" in payload["strategies"][0]["description"]
    assert payload["perStrategy"]["floor-13"]["refusalRate"] == pytest.approx(0.6, abs=1e-3)


async def test_duplicate_strategy_keys_are_rejected(app: FastAPI, secret: str) -> None:
    status, payload = await post(
        app, secret, "/eval/run", {"strategies": [{"key": "same"}, {"key": "same"}]}
    )

    assert status == 400
    assert payload["code"] == "AI_BAD_REQUEST"
    assert "重复" in payload["message"]


async def test_unknown_dataset_is_a_bad_request(app: FastAPI, secret: str) -> None:
    status, payload = await post(app, secret, "/eval/run", {"dataset": "nope"})

    assert status == 400
    assert payload["code"] == "AI_BAD_REQUEST"
    assert "golden_v1" in payload["message"], "错误消息要告诉调用方目前支持什么"


async def test_unknown_extra_field_is_rejected(app: FastAPI, secret: str) -> None:
    """契约是 extra=forbid：多写字段说明调用方与契约已经不一致，必须报出来。"""
    status, payload = await post(app, secret, "/eval/run", {"topk": 10})

    assert status == 422
    assert "detail" in payload


async def test_datasets_endpoint_lists_the_golden_set(app: FastAPI, secret: str) -> None:
    status, payload = await get(app, secret, "/eval/datasets")

    assert status == 200
    assert isinstance(payload, list)
    assert payload[0]["id"] == "golden_v1"
    assert payload[0]["cases"] == 30
    assert payload[0]["answerableCases"] == 20
    assert payload[0]["unanswerableCases"] == 10


async def test_strategies_endpoint_matches_the_defaults(app: FastAPI, secret: str) -> None:
    status, payload = await get(app, secret, "/eval/strategies")

    assert status == 200
    assert [row["key"] for row in payload] == [
        "sparse",
        "dense",
        "hybrid",
        "hybrid+rerank",
        "sparse+floor",
    ]
    assert payload[0]["enableDense"] is False
    assert payload[4]["minScore"] == 11.0
