"""评测接口测试：形状、口径与错误码。

为什么值得测：这个接口的输出**直接变成前端对比表**。列名错了、拒答率的分子分母混了、
错误请求返回 500 而不是 400，都会让「评测结果」被误读 —— 而误读的代价是拿错结论去调检索策略。
测试全部走真实签名头（`tests/signing.py`），顺带把「评测接口受内部签名保护」这件事钉住。

模型同样是**显式配出来的**：默认那五组含 Dense 与 Rerank，所以「没配模型」时
接口必须报 400（见 `test_dense_strategy_without_embedding_model_is_a_readable_400`），
而不是自己找一套桩把数字凑出来。
"""

from __future__ import annotations

import json

import pytest
from fastapi import FastAPI

from app.main import create_app
from app.rag.eval_service import EvalModels, _notes
from app.schemas.eval import EvalModelSource
from tests.fake_providers import install_fake_providers, install_no_providers
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    # 固定时钟：签名向量里的时间戳必须落在 ±60s 窗口内
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    from app.core.internal_auth import InternalRequestVerifier

    install_fake_providers()
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


def test_notes_flag_upstream_failures_before_the_model_source_note() -> None:
    """有题因上游失败被降级成「拒答」时，第一条提示必须先说这件事。

    为什么值得单独一条：那一行指标（recall 0 / 拒答率 1.0）长得跟「策略彻底失效」一模一样。
    实测踩过 —— 标准五组跑到第 4 组时免费嵌入模型 429，`hybrid+rerank` 整行归零，
    而它和重排质量毫无关系。没有这条提示，面板上的假结论会被当成真结论。
    """
    models = EvalModels(source=EvalModelSource.PANEL, names=("some-embed",))

    flagged = _notes(models, error_count=30)

    assert "30" in flagged[0]
    assert "不可用" in flagged[0]
    assert _notes(models, error_count=0)[0].startswith("本次向量与重排")


async def test_sparse_only_run_reports_that_no_model_was_used(app: FastAPI, secret: str) -> None:
    """纯稀疏策略一次模型都不碰：`models` 必须说「没用模型」，且**不配模型也能跑**。

    这条守的是「最省钱的对照实验不该被模型配置挡住」。反过来，
    如果这里报 `fake` 或 `panel`，用户会把纯 BM25 的数字误读成「模型跑出来的」。
    """
    install_no_providers()

    status, payload = await post(
        app,
        secret,
        "/eval/run",
        # 开关要写全：契约里 `enableDense` 默认是 true，只写 key 等于「稀疏 + 向量」两路
        {"strategies": [{"key": "sparse-only", "enableSparse": True, "enableDense": False}]},
    )

    assert status == 200
    assert payload["models"] == "none"
    assert "没有用到模型" in payload["notes"][0]


async def test_dense_strategy_without_embedding_model_is_a_readable_400(
    app: FastAPI, secret: str
) -> None:
    """要跑 Dense 却没配嵌入模型：**跑之前**就报 400 并指路。

    为什么强调「跑之前」：一轮评测要先嵌入整个语料，等第一路 Dense 跑到一半再报错，
    用户是先白等几十秒、再拿到一个他本可以立刻修的错误。
    """
    install_no_providers()

    status, payload = await post(app, secret, "/eval/run", {"strategies": [{"key": "dense"}]})

    assert status == 400
    assert "embedding" in payload["message"]
    assert "模型配置" in payload["message"]


async def test_rerank_strategy_without_rerank_model_says_which_role(
    app: FastAPI, secret: str
) -> None:
    """缺 rerank 角色时报的是 rerank，不能笼统说「模型没配」——
    用户会去检查一个明明填好的 embedding 表单。"""
    from tests.fake_providers import install_roles

    install_roles(["chat", "embedding"])

    status, payload = await post(
        app,
        secret,
        "/eval/run",
        {
            "strategies": [
                {
                    "key": "rerank-only",
                    "enableSparse": True,
                    "enableDense": False,
                    "enableRerank": True,
                }
            ]
        },
    )

    assert status == 400
    assert "rerank" in payload["message"]
    assert "embedding" not in payload["message"]


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
