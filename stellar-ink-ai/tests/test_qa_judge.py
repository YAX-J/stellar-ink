"""决策协议与模型复核：真实请求形状、概率校验、人工结论隔离。"""

import asyncio
import json
import uuid
from dataclasses import replace

import httpx
import pytest

from app.core.internal_auth import InternalRequestVerifier
from app.main import create_app
from app.providers.cloudflare_decision import CloudflareDecisionProvider
from app.providers.errors import ProviderUnavailableError
from app.providers.models import ProviderCapabilities, ProviderConfig
from app.providers.retry import RetryPolicy
from app.rag.qa_eval import QaEvalRuns
from app.rag.qa_judge import judge_case, model_summary
from app.schemas.common import Citation, DoneReason
from app.schemas.qa import QaAnswer
from app.schemas.qa_eval import QaEvalCase, QaEvalCheck, QaEvalJudgeRequest, QaEvalStartRequest
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers
from tests.test_qa import _service


def config():
    return ProviderConfig(
        role="decision",
        provider="cloudflare_decision",
        base_url="https://api.cloudflare.com/client/v4/accounts/test/ai",
        model="@cf/cloudflare/clef-flash",
        api_key="test-token",
        capabilities=ProviderCapabilities(decision=True),
    )


def reply(payload):
    return {
        "success": True,
        "result": {
            "model": "clef-flash",
            "usage": {"input_tokens": 200, "output_tokens": 0},
            "answers": {
                key: {
                    "type": "choice",
                    "choice": "pass",
                    "probabilities": {"pass": 0.95, "fail": 0.03, "uncertain": 0.02},
                }
                for key in payload["questions"]
            },
        },
    }


def provider(handler):
    return CloudflareDecisionProvider(
        config(), transport=httpx.MockTransport(handler), retry=RetryPolicy.disabled()
    )


def case():
    return QaEvalCase(
        case_id="a001",
        question="事实？",
        expected_post_ids=[1],
        expected_answer="已记录的事实",
        status="completed",
        answer_hash="a" * 64,
        answer=QaAnswer(answer="已记录的事实 [1]。"),
    )


@pytest.mark.parametrize("tail", ["", "/", "/run", "/run/", "/run/@cf/cloudflare/clef-flash",
                                  "/run/@cf/cloudflare/clef-flash/"])
async def test_decision_endpoint_does_not_repeat_run_or_model(tail):
    seen = []
    base = "https://api.cloudflare.com:8443/proxy/client/v4/accounts/test/ai"

    def handle(request):
        seen.append(request)
        assert request.url.host == "api.cloudflare.com" and request.url.port == 8443
        assert request.url.path == "/proxy/client/v4/accounts/test/ai/run/@cf/cloudflare/clef-flash"
        assert request.headers["Authorization"] == "Bearer test-token"
        return httpx.Response(200, json=reply(json.loads(request.content)))

    model = CloudflareDecisionProvider(
        replace(config(), base_url=base + tail), transport=httpx.MockTransport(handle),
        retry=RetryPolicy.disabled(),
    )
    try:
        await judge_case(case(), model)
    finally:
        await model.aclose()
    assert len(seen) == 1


async def test_native_protocol_and_unrelated_human_review_remains_pending():
    def handle(request):
        assert request.url.path == "/client/v4/accounts/test/ai/run/@cf/cloudflare/clef-flash"
        assert request.headers["Authorization"] == "Bearer test-token"
        payload = json.loads(request.content)
        assert payload["model"] == "clef-flash" and "messages" not in payload
        assert set(payload["questions"]) == {"answerCorrect", "citationSupport"}
        assert payload["state"]["referenceAnswer"] == "已记录的事实"
        return httpx.Response(200, json=reply(payload))

    row = case()
    model = provider(handle)
    try:
        reviewed = await judge_case(row, model)
    finally:
        await model.aclose()
    assert reviewed.correct is True and reviewed.citations_supported is True
    assert reviewed.usage.total_tokens == 200
    assert row.review.correct is None and row.model_review is None
    row.model_review = reviewed
    assert model_summary([row]).answer_pass_rate == 1


async def test_judge_reentry_is_rejected_and_cancel_unlocks_run(monkeypatch):
    runs = QaEvalRuns(_service)
    started = runs.start(QaEvalStartRequest(dataset="acceptance_v1", max_cases=1), 1)
    result = await runs.step(started.run_id, 1)
    row = result.cases[0]
    request = QaEvalJudgeRequest(
        run_id=result.run_id, case_id=row.case_id, answer_hash=row.answer_hash
    )
    model = provider(lambda r: pytest.fail("替身不应调用 HTTP"))
    entered = asyncio.Event()

    async def slow(*args):
        entered.set()
        await asyncio.Event().wait()

    monkeypatch.setattr(model, "decide", slow)

    class Registry:
        def decision_model(self):
            return model

    monkeypatch.setattr("app.rag.qa_eval.runtime.registry", Registry)
    task = asyncio.create_task(runs.judge(request, 1))
    try:
        await asyncio.wait_for(entered.wait(), timeout=2)
        with pytest.raises(ValueError, match="等待结束"):
            await runs.judge(request, 1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert not runs.view(result.run_id, 1).busy
        assert runs.view(result.run_id, 1).cases[0].model_review is None
    finally:
        await model.aclose()


@pytest.mark.parametrize(
    "probabilities,choice",
    [
        ({"pass": 0.6, "fail": 0.3, "uncertain": 0.1}, "pass"),
        ({"pass": 0.1, "fail": 0.1, "uncertain": 0.8}, "uncertain"),
        ({"pass": 0.01, "fail": 0.98, "uncertain": 0.01}, "fail"),
    ],
)
async def test_uncertainty_and_failures_require_human_attention(probabilities, choice):
    def handle(request):
        data = reply(json.loads(request.content))
        for decision in data["result"]["answers"].values():
            decision.update(choice=choice, probabilities=probabilities)
        return httpx.Response(200, json=data)

    model = provider(handle)
    try:
        reviewed = await judge_case(case(), model)
    finally:
        await model.aclose()
    assert reviewed.needs_human_review
    assert reviewed.correct is (False if choice == "fail" else None)


@pytest.mark.parametrize("change", ["missing", "nan", "range", "choice", "sum", "usage"])
async def test_malformed_decisions_are_errors_not_passing_scores(change):
    def handle(request):
        data = reply(json.loads(request.content))
        first = data["result"]["answers"]["answerCorrect"]
        if change == "missing":
            del data["result"]["answers"]["answerCorrect"]
        elif change == "nan":
            # 标准 JSON 不支持 NaN，字符串形式也不得被当成数值。
            first["probabilities"]["pass"] = "nan"
        elif change == "range":
            first["probabilities"]["pass"] = 1.5
        elif change == "choice":
            first["choice"] = "fail"
        elif change == "sum":
            first["probabilities"]["pass"] = 0.5
        else:
            data["result"]["usage"]["input_tokens"] = True
        return httpx.Response(200, json=data)

    model = provider(handle)
    try:
        with pytest.raises(ProviderUnavailableError):
            await judge_case(case(), model)
    finally:
        await model.aclose()


async def test_pure_refusal_has_no_citation_support_question():
    def handle(request):
        payload = json.loads(request.content)
        assert set(payload["questions"]) == {"answerCorrect"}
        return httpx.Response(200, json=reply(payload))

    row = case()
    row.expected_post_ids = []
    row.answer = QaAnswer(
        answer="缺少依据", done_reason=DoneReason.REFUSED, evidence_sufficient=False
    )
    model = provider(handle)
    try:
        reviewed = await judge_case(row, model)
    finally:
        await model.aclose()
    assert reviewed.citations_supported is None


async def test_state_is_not_silently_truncated():
    model = provider(lambda r: pytest.fail("超预算不应调用供应商"))
    row = case()
    row.answer.answer = "长" * 10_000
    try:
        with pytest.raises(ValueError, match="上下文预算"):
            await judge_case(row, model)
    finally:
        await model.aclose()


async def test_structural_failure_cannot_be_hidden_by_model_pass():
    row = case()
    row.answer.citations = [
        Citation(post_id=1, title="原文", chunk_index=0, snippet="已记录的事实")
    ]
    row.automatic = QaEvalCheck(checked=1, source_matched=1, out_of_range=[9])
    model = provider(lambda r: httpx.Response(200, json=reply(json.loads(r.content))))
    try:
        reviewed = await judge_case(row, model)
    finally:
        await model.aclose()
    assert reviewed.correct is True and reviewed.citations_supported is True
    assert reviewed.needs_human_review


async def test_judge_api_checks_identity_hash_and_forbids_client_scores(monkeypatch):
    runs = QaEvalRuns(_service)
    started = runs.start(QaEvalStartRequest(dataset="acceptance_v1", max_cases=1), 1)
    answered = await runs.step(started.run_id, 1)
    row = answered.cases[0]
    calls = []

    def handle(request):
        calls.append(request)
        return httpx.Response(200, json=reply(json.loads(request.content)))

    model = provider(handle)

    class Registry:
        def decision_model(self):
            return model

    monkeypatch.setattr("app.rag.qa_eval.runtime.registry", Registry)
    monkeypatch.setattr("app.api.v1.qa_eval.runs", runs)
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    secret = str(load_vector()["secret"])
    app = create_app(verifier=InternalRequestVerifier(secret))
    path = "/eval/qa/judge"
    payload = {"runId": started.run_id, "caseId": row.case_id, "answerHash": row.answer_hash}

    async def post(data, owner=1, role="ADMIN"):
        body = json.dumps(data)
        headers = signed_headers(
            "POST", path, secret=secret, body=body, user_id=owner, role=role, nonce=uuid.uuid4().hex
        )
        headers["Content-Type"] = "application/json"
        return await call(app, "POST", path, content=body, headers=headers)

    try:
        assert (await call(app, "POST", path, json=payload)).status_code == 401
        assert (await post(payload, role="AUTHOR")).status_code == 403
        assert (await post(payload, owner=2)).status_code == 400
        assert (await post({**payload, "answerHash": "0" * 64})).status_code == 400
        assert (await post({**payload, "correct": True})).status_code == 422
        assert not calls
        result = await post(payload)
        assert result.status_code == 200
        data = result.json()
        assert data["modelSummary"]["reviewed"] == 1
        assert data["summary"]["answerAccuracy"] is None
        assert data["cases"][0]["modelReview"]["answerHash"] == row.answer_hash
        assert data["stepUsage"]["totalTokens"] == 200
        assert len(calls) == 1
    finally:
        await model.aclose()


async def test_run_judge_checks_hash_owner_and_preserves_manual_marks(monkeypatch):
    runs = QaEvalRuns(_service)
    started = runs.start(QaEvalStartRequest(dataset="acceptance_v1", max_cases=1), 1)
    answered = await runs.step(started.run_id, 1)
    original = runs.get(started.run_id, 1).snapshot.cases[0]
    original.review.correct = False
    model = provider(lambda r: httpx.Response(200, json=reply(json.loads(r.content))))

    class Registry:
        def decision_model(self):
            return model

    monkeypatch.setattr("app.rag.qa_eval.runtime.registry", Registry)
    request = QaEvalJudgeRequest(
        run_id=started.run_id, case_id=original.case_id, answer_hash=original.answer_hash
    )
    try:
        with pytest.raises(ValueError):
            await runs.judge(request, 2)
        with pytest.raises(ValueError):
            await runs.judge(request.model_copy(update={"answer_hash": "0" * 64}), 1)
        judged = await runs.judge(request, 1)
        assert judged.cases[0].review.correct is False
        assert judged.cases[0].automatic == answered.cases[0].automatic
        assert judged.step_usage.total_tokens == 200
        assert runs.view(started.run_id, 1).step_usage is None

        # 失败不覆盖上一次模型结果，也不改写生成答案或人工结果。
        async def fail(*args):
            raise ProviderUnavailableError("服务不可用")

        monkeypatch.setattr(model, "decide", fail)
        with pytest.raises(ProviderUnavailableError):
            await runs.judge(request, 1)
        assert not runs.view(started.run_id, 1).busy
        assert runs.view(started.run_id, 1).cases[0].model_review == judged.cases[0].model_review
    finally:
        await model.aclose()
