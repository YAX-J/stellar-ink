"""报告存档：重启可读、账号隔离、人工复核可续存、读取不调用模型。"""

import json
import uuid

import pytest

from app.core.internal_auth import InternalRequestVerifier
from app.main import create_app
from app.rag.qa_eval import QaEvalRuns
from app.rag.qa_reports import QaReportStore
from app.schemas.qa_eval import QaEvalReviewRequest, QaEvalStartRequest
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers
from tests.test_qa import _service


async def archived(tmp_path):
    store = QaReportStore(tmp_path)
    runs = QaEvalRuns(_service, store)
    start = runs.start(QaEvalStartRequest(dataset="acceptance_v1", max_cases=1), 1)
    result = await runs.step(start.run_id, 1)
    return store, result


async def test_report_survives_restart_and_read_does_not_call_models(tmp_path):
    store, result = await archived(tmp_path)
    assert result.report_saved_at is not None and not result.report_error

    def no_model():
        pytest.fail("读取或人工复核报告不应装配模型")

    cold = QaEvalRuns(no_model, store)
    restored = cold.latest(1)
    assert restored.run_id == result.run_id
    assert restored.step_usage is None
    assert restored.cases[0].answer == result.cases[0].answer
    assert cold.latest(2) is None
    with pytest.raises(ValueError):
        cold.view(result.run_id, 2)
    row = restored.cases[0]
    reviewed = cold.review(QaEvalReviewRequest(run_id=restored.run_id, case_id=row.case_id,
                          answer_hash=row.answer_hash, correct=False, note="还需核对"), 1)
    assert reviewed.summary.correct_reviewed == 1
    again = QaEvalRuns(no_model, store).latest(1)
    assert again.cases[0].review.correct is False
    assert again.cases[0].review.note == "还需核对"
    assert again.summary.answer_accuracy == 0


async def test_unfinished_run_does_not_replace_saved_report(tmp_path):
    store, previous = await archived(tmp_path)
    runs = QaEvalRuns(_service, store)
    started = runs.start(QaEvalStartRequest(dataset="acceptance_v1", max_cases=3), 1)
    await runs.step(started.run_id, 1)
    assert runs.latest(1).run_id == previous.run_id
    assert not list(tmp_path.glob("*.tmp"))


async def test_corrupt_and_wrong_owner_reports_are_not_empty_success(tmp_path):
    store, _ = await archived(tmp_path)
    path = tmp_path / "qa-eval-1.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["owner"] = 2
    path.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(ValueError, match="无法读取"):
        store.load(1)
    path.write_text("{broken", encoding="utf-8")
    with pytest.raises(ValueError, match="无法读取"):
        store.load(1)


async def test_archive_failure_keeps_successful_answer_and_usage(tmp_path, monkeypatch):
    store = QaReportStore(tmp_path)

    def denied(*args):
        raise PermissionError("模拟存档不可写")

    monkeypatch.setattr(store, "save", denied)
    runs = QaEvalRuns(_service, store)
    started = runs.start(QaEvalStartRequest(dataset="acceptance_v1", max_cases=1), 1)
    result = await runs.step(started.run_id, 1)
    assert result.cases[0].status == "completed" and result.cases[0].answer is not None
    assert result.step_usage.total_tokens == 120
    assert result.report_error and result.report_saved_at is None


async def test_latest_route_is_signed_admin_and_owner_scoped(tmp_path, monkeypatch):
    store, result = await archived(tmp_path)
    monkeypatch.setattr("app.api.v1.qa_eval.runs", QaEvalRuns(_service, store))
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    secret = str(load_vector()["secret"])
    app = create_app(verifier=InternalRequestVerifier(secret))
    path = "/eval/qa/latest"

    async def get(owner=1, role="ADMIN"):
        headers = signed_headers("GET", path, secret=secret, user_id=owner,
                                 role=role, nonce=uuid.uuid4().hex)
        return await call(app, "GET", path, headers=headers)

    assert (await call(app, "GET", path)).status_code == 401
    assert (await get(role="AUTHOR")).status_code == 403
    assert (await get(owner=2)).json() is None
    response = await get()
    assert response.status_code == 200 and response.json()["runId"] == result.run_id
    assert response.json()["stepUsage"] is None
