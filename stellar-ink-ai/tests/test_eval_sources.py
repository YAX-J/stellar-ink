"""来源编号复用、正文变化与旧报告不得制造伪误拒分数。"""

import hashlib
import json
from dataclasses import replace

import pytest

from app.rag.eval_runner import EvalCase, EvalDataset, datasets_available
from app.rag.eval_service import default_dataset_path
from app.rag.eval_sources import require_saved_source_proof, validate_sources
from app.rag.pipeline import IndexedChunk, build_corpus
from app.rag.seed_corpus import load_seed_posts
from tests.test_scripts import load_script


@pytest.fixture()
def fixture(tmp_path):
    path = tmp_path / "holdout.json"
    path.write_text(
        json.dumps(
            {
                "purpose": "holdout",
                "name": "测试",
                "cases": [
                    {"caseId": "q1", "question": "这篇文章说等多久？", "expectedPostIds": [16]},
                ],
            }
        ),
        encoding="utf-8",
    )
    manifest = {
        "bindings": [
            {
                "caseIds": ["q1"],
                "id": 16,
                "title": "指定文章",
                "quotes": ["从六十秒缩短到五秒"],
                "version": "a" * 16,
            }
        ]
    }
    path.with_suffix(".sources.json").write_text(json.dumps(manifest), encoding="utf-8")
    chunk = IndexedChunk(
        "p16:c0",
        16,
        "指定文章\n从六十秒缩短到五秒",
        {"text": "从六十秒缩短到五秒", "version": "a" * 16},
        "指定文章",
    )
    return path, EvalDataset.load(path), chunk


def test_same_id_and_other_notes_cannot_replace_the_named_source(fixture):
    path, dataset, chunk = fixture
    reused = replace(chunk, title="微服务验证之星", text="链路验证", payload={"text": "链路验证"})
    related_note = replace(chunk, kind="note", chunk_id="n16:c0")
    with pytest.raises(ValueError, match="q1 / post:16.*标题不符"):
        validate_sources(path, dataset.cases, [reused, related_note])


def test_missing_source_version_and_quote_drift_are_reported(fixture):
    path, dataset, chunk = fixture
    with pytest.raises(ValueError, match="来源不存在"):
        validate_sources(path, dataset.cases, [])
    with pytest.raises(ValueError, match="版本已变化"):
        validate_sources(
            path, dataset.cases, [replace(chunk, payload={**chunk.payload, "version": "b" * 16})]
        )
    with pytest.raises(ValueError, match="证据不在"):
        validate_sources(
            path,
            dataset.cases,
            [replace(chunk, payload={**chunk.payload, "text": "没有记载等待时间"})],
        )


def test_manifest_cannot_skip_an_answerable_source_or_be_deleted(fixture):
    path, dataset, chunk = fixture
    with pytest.raises(ValueError, match="缺少对应"):
        validate_sources(path, [EvalCase("q2", "问题", [17])], [chunk])
    path.with_suffix(".sources.json").unlink()
    with pytest.raises(ValueError, match="缺少来源清单"):
        validate_sources(path, dataset.cases, [chunk])


def test_saved_report_requires_bound_source_proof(fixture):
    path, dataset, chunk = fixture
    proof = validate_sources(path, dataset.cases, [chunk])
    result = {
        "rows": [{"caseId": "q1"}],
        "corpusFingerprint": proof["corpusFingerprint"],
        "datasetFingerprint": hashlib.sha256(path.read_bytes()).hexdigest(),
    }
    with pytest.raises(ValueError, match="缺少本轮来源校验"):
        require_saved_source_proof(path, result)
    result["sourceValidation"] = proof
    require_saved_source_proof(path, result)
    result["corpusFingerprint"] = "另一个版本"
    with pytest.raises(ValueError, match="来源校验"):
        require_saved_source_proof(path, result)


def test_h007_regression_detects_online_reused_article_id_without_model_calls():
    path = default_dataset_path("holdout_v1")
    dataset = EvalDataset.load(path)
    chunks = build_corpus(load_seed_posts())
    # 离线种子满足旧标注；线上同号文章被替换后必须在调用模型前拒绝评测。
    validate_sources(path, dataset.cases, chunks)
    current = [replace(c, title="微服务验证之星") if c.post_id == 16 else c for c in chunks]
    with pytest.raises(ValueError, match="h005,h006,h007,h008 / post:16.*标题不符"):
        validate_sources(path, dataset.cases, current)
    validate_sources(path, dataset.cases[:3], current)


def test_collect_rejects_bad_source_before_login_or_paid_requests(fixture, monkeypatch, tmp_path):
    path, _, chunk = fixture
    runner = load_script("eval_qa_answers")
    monkeypatch.setenv("QA_EVAL_USERNAME", "test")
    monkeypatch.setenv("QA_EVAL_PASSWORD", "test")
    monkeypatch.setattr(runner, "default_dataset_path", lambda name: path)
    monkeypatch.setattr(
        runner.corpus, "cached_corpus", lambda: [replace(chunk, title="另一篇文章")]
    )
    monkeypatch.setattr(
        runner.httpx, "Client", lambda **kwargs: pytest.fail("来源失败不应发起网关请求")
    )
    with pytest.raises(ValueError, match="来源校验失败"):
        runner.collect("http://example.invalid", "holdout", tmp_path / "report.json")


def test_panel_start_rejects_before_creating_a_run_or_calling_chat(fixture, monkeypatch):
    from app.rag.qa_eval import QaEvalRuns
    from app.schemas.qa_eval import QaEvalStartRequest
    from tests.test_qa import _Chat, _service

    path, _, chunk = fixture
    chat = _Chat()
    service = _service(chat)
    monkeypatch.setattr("app.rag.qa_eval.default_dataset_path", lambda name: path)
    monkeypatch.setattr(
        "app.rag.qa_eval.corpus.cached_corpus", lambda: [replace(chunk, title="另一篇文章")]
    )
    runs = QaEvalRuns(lambda: service)
    with pytest.raises(ValueError, match="来源校验失败"):
        runs.start(QaEvalStartRequest(dataset="holdout"), 1)
    assert not runs.runs and not chat.calls


@pytest.mark.parametrize("name", ["holdout_v2", "holdout_v3"])
def test_new_holdouts_are_disjoint_and_have_real_source_bindings(name):
    path = default_dataset_path(name)
    dataset = EvalDataset.load(path)
    earlier = {
        c.question
        for previous in ("golden_v1", "acceptance_v1", "holdout_v1", "holdout_v2", "holdout_v3")
        if previous != name
        for c in EvalDataset.load(default_dataset_path(previous)).cases
    }
    assert len(dataset.cases) == 16 and dataset.answerable_count == 8
    assert not earlier & {c.question for c in dataset.cases}
    seed_chunks = build_corpus(load_seed_posts())
    # v2 针对当前公开库：种子中的旧文章16仍存在，缺失来源题在种子上反而应被拦下。
    with pytest.raises(ValueError, match="(?:v209|v309).*已公开"):
        validate_sources(path, dataset.cases, seed_chunks)
    current = [
        replace(c, title="微服务验证之星", text="链路验证", payload={"text": "链路验证"})
        if c.post_id == 16
        else c
        for c in seed_chunks
    ]
    validate_sources(path, dataset.cases, current)
    with pytest.raises(ValueError, match="不能用于标定"):
        load_script("calibrate_hybrid_score").load_calibration_dataset(name)
    assert not any(name.endswith(".sources.json") for name in datasets_available(path.parent))
