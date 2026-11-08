"""完整问答验收：隔离、逐题调度、失败状态与人工复核分母。"""

import asyncio
import json
import uuid

import pytest

from app.core.internal_auth import InternalRequestVerifier
from app.main import create_app
from app.providers.config_source import ProviderConfigError
from app.rag.qa_eval import MAX_RUNS, RUN_TTL_SECONDS, QaEvalRuns, summary_of
from app.schemas.common import DoneReason
from app.schemas.qa import QaAnswer
from app.schemas.qa_eval import QaEvalCase, QaEvalReviewRequest, QaEvalStartRequest
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers
from tests.test_qa import _Chat, _service


@pytest.fixture()
def runs() -> QaEvalRuns:
    return QaEvalRuns(lambda: _service())


def start(runs: QaEvalRuns, size: int = 3):
    return runs.start(QaEvalStartRequest(dataset="acceptance_v1", max_cases=size), 1)


async def test_steps_are_real_answers_with_usage_and_pending_reviews(runs: QaEvalRuns) -> None:
    initial = start(runs)
    assert initial.summary.answer_accuracy is None
    assert initial.summary.citation_support_rate is None
    first = await runs.step(initial.run_id, 1)
    assert first.completed == 1 and first.total == 3
    assert [c.status for c in first.cases] == ["completed", "pending", "pending"]
    row = first.cases[0]
    assert row.answer is not None and row.automatic is not None
    assert row.answer.answer == "答案是 [1]。"
    assert row.automatic.source_matched == len(row.answer.citations) > 0
    assert first.step_usage == row.answer.usage
    assert first.summary.answer_accuracy is None
    # 读取或复核不应重复上报上一题用量；响应也不能修改服务端记录。
    assert runs.view(initial.run_id, 1).step_usage is None
    first.cases[1].status = "completed"
    assert runs.view(initial.run_id, 1).cases[1].status == "pending"
    second = await runs.step(initial.run_id, 1)
    assert second.summary.total_tokens == 240
    assert second.step_usage.total_tokens == 120


async def test_review_rates_use_only_reviewed_cases_and_can_be_cleared(runs: QaEvalRuns) -> None:
    initial = start(runs, 2)
    first = await runs.step(initial.run_id, 1)
    row = first.cases[0]
    request = QaEvalReviewRequest(
        run_id=first.run_id, case_id=row.case_id, answer_hash=row.answer_hash,
        correct=True, citations_supported=False, note=None,
    )
    reviewed = runs.review(request, 1)
    assert reviewed.summary.correct_reviewed == 1
    assert reviewed.summary.answer_accuracy == 1
    assert reviewed.summary.citation_support_rate == 0
    assert reviewed.cases[1].review.correct is None
    with pytest.raises(ValueError, match="答案已变化"):
        runs.review(request.model_copy(update={"answer_hash": "0" * 64}), 1)
    cleared = runs.review(request.model_copy(update={
        "correct": None, "citations_supported": None,
    }), 1)
    assert cleared.summary.answer_accuracy is None
    assert cleared.summary.citation_support_rate is None
    assert cleared.cases[0].automatic == row.automatic


async def test_owner_and_finished_case_are_required(runs: QaEvalRuns) -> None:
    initial = start(runs, 1)
    with pytest.raises(ValueError, match="不存在"):
        await runs.step(initial.run_id, 2)
    with pytest.raises(ValueError, match="题目未完成"):
        runs.review(QaEvalReviewRequest(
            run_id=initial.run_id, case_id=initial.cases[0].case_id, answer_hash="0" * 64,
        ), 1)
    await runs.step(initial.run_id, 1)
    with pytest.raises(ValueError, match="没有待执行"):
        await runs.step(initial.run_id, 1)


async def test_concurrent_step_is_rejected_and_cancellation_unlocks_run() -> None:
    entered = asyncio.Event()
    release = asyncio.Event()

    class SlowChat(_Chat):
        async def chat(self, *args, **kwargs):
            entered.set()
            await release.wait()
            return await super().chat(*args, **kwargs)

    runs = QaEvalRuns(lambda: _service(SlowChat()))
    initial = start(runs)
    task = asyncio.create_task(runs.step(initial.run_id, 1))
    await asyncio.wait_for(entered.wait(), timeout=2)
    assert runs.view(initial.run_id, 1).busy
    with pytest.raises(ValueError, match="重复提交"):
        await runs.step(initial.run_id, 1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    result = runs.view(initial.run_id, 1)
    assert not result.busy and result.completed == 1
    assert result.cases[0].status == "error"
    assert result.summary.errors == 1
    assert result.summary.unanswerable_refused == 0


async def test_execution_failure_is_not_a_refusal() -> None:
    class FailingChat(_Chat):
        async def chat(self, *args, **kwargs):
            raise RuntimeError("不可向用户泄漏的内部细节")

    runs = QaEvalRuns(lambda: _service(FailingChat()))
    initial = start(runs)
    with pytest.raises(RuntimeError):
        await runs.step(initial.run_id, 1)
    result = runs.view(initial.run_id, 1)
    assert result.summary.errors == 1
    assert result.cases[0].answer is None
    assert "内部细节" not in result.cases[0].error
    assert result.summary.answer_accuracy is None


async def test_changed_model_requires_a_new_run(runs: QaEvalRuns, monkeypatch) -> None:
    initial = start(runs)
    monkeypatch.setattr("app.rag.qa_eval.runtime.fingerprint", lambda: "changed")
    with pytest.raises(ValueError, match="模型或公开语料已变化"):
        await runs.step(initial.run_id, 1)
    assert runs.view(initial.run_id, 1).completed == 0


def test_records_are_bounded_and_expire(runs: QaEvalRuns) -> None:
    first = start(runs)
    for _ in range(MAX_RUNS):
        start(runs)
    assert len(runs.runs) == MAX_RUNS
    with pytest.raises(ValueError):
        runs.get(first.run_id, 1)
    last = next(iter(runs.runs))
    runs.runs[last].touched -= RUN_TTL_SECONDS + 1
    with pytest.raises(ValueError):
        runs.get(last, 1)


def test_refusal_and_budget_exhaustion_are_separate_from_semantic_reviews() -> None:
    cases = [QaEvalCase(
        case_id="negative", question="未记录的事实？", expected_post_ids=[], status="completed",
        answer=QaAnswer(answer="没有依据", done_reason=DoneReason.REFUSED,
                        evidence_sufficient=False),
    ), QaEvalCase(
        case_id="truncated", question="答案？", expected_post_ids=[1], status="completed",
        answer=QaAnswer(answer="尚未写完", done_reason=DoneReason.LENGTH),
    )]
    summary = summary_of(cases)
    assert summary.unanswerable_refused == 1 and summary.truncated == 1
    assert summary.answer_accuracy is None and summary.citation_support_rate is None


async def test_routes_require_admin_signature_and_isolate_owners(runs, monkeypatch) -> None:
    monkeypatch.setattr("app.api.v1.qa_eval.runs", runs)
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    secret = str(load_vector()["secret"])
    app = create_app(verifier=InternalRequestVerifier(secret))

    async def request(method, path, payload=None, owner=1, role="ADMIN"):
        body = "" if payload is None else json.dumps(payload)
        headers = signed_headers(method, path, secret=secret, body=body, user_id=owner,
                                 role=role, nonce=uuid.uuid4().hex)
        headers["Content-Type"] = "application/json"
        return await call(app, method, path, content=body, headers=headers)

    assert (await call(app, "POST", "/eval/qa/start", json={})).status_code == 401
    denied = await request("POST", "/eval/qa/start", {"dataset": "acceptance_v1"}, role="AUTHOR")
    assert denied.status_code == 403
    started = await request("POST", "/eval/qa/start", {"dataset": "acceptance_v1", "maxCases": 1})
    assert started.status_code == 200
    run_id = started.json()["runId"]
    assert (await request("GET", f"/eval/qa/runs/{run_id}", owner=2)).status_code == 400
    step = await request("POST", "/eval/qa/step", {"runId": run_id})
    assert step.status_code == 200 and step.json()["completed"] == 1
    row = step.json()["cases"][0]
    review = {"runId": run_id, "caseId": row["caseId"], "answerHash": row["answerHash"],
              "correct": None, "citationsSupported": None, "note": None}
    assert (await request("PUT", "/eval/qa/review", review)).status_code == 200
    # 客户端不能提交自动检查结果或伪造运行用量。
    assert (await request("PUT", "/eval/qa/review", {**review, "automatic": {}})).status_code == 422
    another = await request("POST", "/eval/qa/start", {"dataset": "acceptance_v1", "maxCases": 2})

    def missing_config():
        raise ProviderConfigError("缺少生成模型配置")

    monkeypatch.setattr(runs, "service_factory", missing_config)
    unavailable = await request("POST", "/eval/qa/step", {"runId": another.json()["runId"]})
    assert unavailable.status_code == 400
    assert runs.view(another.json()["runId"], 1).completed == 0
