"""逐题执行真实问答。运行结果由服务端保管，复核不能改写自动检查。

有界进程内记录按签名用户隔离；一次HTTP只执行一题，前端可停止后续调度。
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass

from starlette.concurrency import run_in_threadpool

from app.agents.verifier import chunk_index_of, verify
from app.core.trace import current_trace_id
from app.providers import runtime
from app.providers.errors import ProviderError
from app.rag import corpus
from app.rag.eval_runner import EvalDataset
from app.rag.eval_service import default_dataset_path
from app.rag.eval_sources import validate_sources
from app.rag.qa import QaService
from app.rag.qa_judge import judge_case, model_summary
from app.rag.qa_reports import QaReportStore, SavedQaReport
from app.rag.retrieval_profile import corpus_fingerprint
from app.schemas.common import DoneReason, Usage
from app.schemas.qa import QaStreamRequest
from app.schemas.qa_eval import (
    QaEvalCase,
    QaEvalCheck,
    QaEvalJudgeRequest,
    QaEvalReview,
    QaEvalReviewRequest,
    QaEvalSnapshot,
    QaEvalStartRequest,
    QaEvalSummary,
)

MAX_RUNS = 16
RUN_TTL_SECONDS = 3600
logger = logging.getLogger(__name__)


@dataclass
class Run:
    owner: int
    snapshot: QaEvalSnapshot
    corpus_hash: str
    model_hash: str
    touched: float
    judge_hash: str | None = None


def is_fact_answer(case: QaEvalCase) -> bool:
    return (
        case.answer is not None
        and case.answer.done_reason == DoneReason.STOP
        and case.answer.evidence_sufficient
    )


def summary_of(cases: list[QaEvalCase]) -> QaEvalSummary:
    result = QaEvalSummary(errors=sum(c.status == "error" for c in cases))
    for case in cases:
        answer = case.answer
        if case.status != "completed" or answer is None:
            continue
        refused = answer.done_reason == DoneReason.REFUSED and not answer.evidence_sufficient
        result.total_tokens += answer.usage.total_tokens
        result.returned_citations += len(answer.citations)
        result.truncated += answer.done_reason == DoneReason.LENGTH
        if not case.expected_post_ids:
            result.unanswerable_completed += 1
            result.unanswerable_refused += refused
        else:
            result.false_refusals += refused
        if case.automatic:
            result.source_matched += case.automatic.source_matched
            result.out_of_range += len(case.automatic.out_of_range)
            result.uncited_refusals += case.automatic.uncited and refused
            result.uncited_answers += case.automatic.uncited and not refused
        if case.review.correct is not None:
            result.correct_reviewed += 1
            result.correct_passed += case.review.correct
        if is_fact_answer(case) and case.review.citations_supported is not None:
            result.support_reviewed += 1
            result.support_passed += case.review.citations_supported
    if result.returned_citations:
        result.source_match_rate = result.source_matched / result.returned_citations
    if result.correct_reviewed:
        result.answer_accuracy = result.correct_passed / result.correct_reviewed
    if result.support_reviewed:
        result.citation_support_rate = result.support_passed / result.support_reviewed
    return result


class QaEvalRuns:
    def __init__(
        self, service_factory: Callable[[], QaService], report_store: QaReportStore | None = None
    ) -> None:
        self.runs: dict[str, Run] = {}
        self.service_factory = service_factory
        self.report_store = report_store

    def _make_room(self) -> None:
        if len(self.runs) < MAX_RUNS:
            return
        idle = [key for key, run in self.runs.items() if not run.snapshot.busy]
        if not idle:
            raise ValueError("评测运行数达到上限，请稍后再试")
        del self.runs[min(idle, key=lambda key: self.runs[key].touched)]

    def _prune(self) -> None:
        now = time.monotonic()
        self.runs = {
            key: run
            for key, run in self.runs.items()
            if run.snapshot.busy or now - run.touched < RUN_TTL_SECONDS
        }

    def get(self, run_id: str, owner: int) -> Run:
        self._prune()
        run = self.runs.get(run_id)
        if run is None and self.report_store:
            saved = self.report_store.load(owner)
            if saved and saved.snapshot.run_id == run_id:
                self._make_room()
                run = Run(
                    owner,
                    saved.snapshot,
                    saved.corpus_hash,
                    saved.model_hash,
                    time.monotonic(),
                    saved.judge_hash,
                )
                self.runs[run_id] = run
        if run is None or run.owner != owner:
            raise ValueError("本轮评测已过期或不存在，请重新开始（服务重启会清除运行记录）")
        run.touched = time.monotonic()
        return run

    def start(self, request: QaEvalStartRequest, owner: int) -> QaEvalSnapshot:
        self._prune()
        if any(run.owner == owner and run.snapshot.busy for run in self.runs.values()):
            raise ValueError("当前题仍在执行，请等待完成后再开始新一轮")
        dataset = EvalDataset.load(default_dataset_path(request.dataset))
        self.service_factory()  # 预检角色与公开语料，不调用模型。
        cases = dataset.cases[: request.max_cases] if request.max_cases else dataset.cases[:30]
        validate_sources(default_dataset_path(request.dataset), cases, corpus.cached_corpus())
        self._make_room()
        run_id = uuid.uuid4().hex
        snapshot = QaEvalSnapshot(
            run_id=run_id,
            dataset=request.dataset,
            dataset_label=dataset.name,
            total=len(cases),
            completed=0,
            cases=[
                QaEvalCase(
                    case_id=c.case_id,
                    question=c.question,
                    expected_post_ids=c.expected_post_ids,
                    expected_answer=c.expected_answer,
                )
                for c in cases
            ],
            summary=QaEvalSummary(),
        )
        self.runs[run_id] = Run(
            owner,
            snapshot,
            corpus_fingerprint(corpus.cached_corpus()),
            runtime.fingerprint(),
            time.monotonic(),
        )
        return snapshot.model_copy(deep=True)

    def view(self, run_id: str, owner: int, usage: Usage | None = None) -> QaEvalSnapshot:
        run = self.get(run_id, owner)
        run.snapshot.summary = summary_of(run.snapshot.cases)
        run.snapshot.model_summary = model_summary(run.snapshot.cases)
        run.snapshot.execution_available = bool(run.corpus_hash and run.model_hash)
        return run.snapshot.model_copy(deep=True, update={"step_usage": usage})

    def latest(self, owner: int) -> QaEvalSnapshot | None:
        saved = self.report_store.load(owner) if self.report_store else None
        return self.view(saved.snapshot.run_id, owner) if saved else None

    def _save_report(self, run: Run) -> None:
        if not self.report_store or run.snapshot.completed != run.snapshot.total:
            return
        run.snapshot.summary = summary_of(run.snapshot.cases)
        run.snapshot.model_summary = model_summary(run.snapshot.cases)
        try:
            run.snapshot.report_saved_at = self.report_store.save(
                SavedQaReport(
                    owner=run.owner,
                    snapshot=run.snapshot,
                    corpus_hash=run.corpus_hash,
                    model_hash=run.model_hash,
                    judge_hash=run.judge_hash,
                )
            )
            run.snapshot.report_error = ""
        except (OSError, ValueError):
            logger.exception(
                "完整问答报告保存失败：runId=%s owner=%s", run.snapshot.run_id, run.owner
            )
            run.snapshot.report_error = (
                "本轮结果未能保存为最近报告，请先导出；已完成的答案和复核仍保留在当前运行中"
            )

    async def step(self, run_id: str, owner: int) -> QaEvalSnapshot:
        run = self.get(run_id, owner)
        if run.snapshot.busy:
            raise ValueError("当前题正在执行，请勿重复提交")
        case = next((c for c in run.snapshot.cases if c.status == "pending"), None)
        if case is None:
            raise ValueError("本轮已没有待执行题目")
        run.snapshot.busy = True
        usage = None
        try:
            service = await run_in_threadpool(self.service_factory)
            current_chunks = await run_in_threadpool(corpus.cached_corpus)
            dataset = EvalDataset.load(default_dataset_path(run.snapshot.dataset))
            selected_ids = {item.case_id for item in run.snapshot.cases}
            validate_sources(
                default_dataset_path(run.snapshot.dataset),
                [item for item in dataset.cases if item.case_id in selected_ids],
                current_chunks,
            )
            if (
                run.corpus_hash != corpus_fingerprint(current_chunks)
                or run.model_hash != runtime.fingerprint()
            ):
                raise ValueError("模型或公开语料已变化，请重新开始，避免混用两轮结果")
            case.status = "running"
            answer = await service.answer(QaStreamRequest(question=case.question, top_k=5))
            checked = verify(
                answer.answer, answer.citations, index=chunk_index_of(service.pipeline.corpus)
            )
            case.answer = answer
            case.automatic = QaEvalCheck(
                checked=checked.checked,
                source_matched=checked.checked
                - sum(p.kind == "snippetNotFound" for p in checked.problems),
                missing_sources=len(answer.citations) - checked.checked,
                out_of_range=checked.out_of_range,
                uncited=checked.uncited,
            )
            raw = json.dumps(
                [case.case_id, case.question, answer.model_dump(mode="json")],
                ensure_ascii=False,
                sort_keys=True,
            )
            case.answer_hash = hashlib.sha256(raw.encode()).hexdigest()
            case.status = "completed"
            usage = answer.usage
        except asyncio.CancelledError:
            if case.status != "running":
                raise
            case.status = "error"
            case.error = "本题等待中断，请重新开始后复测"
            raise
        except Exception as error:
            if case.status != "running":
                raise
            case.status = "error"
            case.error = (
                str(error) if isinstance(error, ProviderError) else "本题执行失败，请按追踪编号排查"
            )
            case.trace_id = current_trace_id() or ""
            raise
        finally:
            run.snapshot.busy = False
            run.snapshot.completed = sum(
                c.status in {"completed", "error"} for c in run.snapshot.cases
            )
            run.touched = time.monotonic()
            self._save_report(run)
        return self.view(run_id, owner, usage)

    def review(self, request: QaEvalReviewRequest, owner: int) -> QaEvalSnapshot:
        run = self.get(request.run_id, owner)
        case = next((c for c in run.snapshot.cases if c.case_id == request.case_id), None)
        if case is None or case.status != "completed" or request.answer_hash != case.answer_hash:
            raise ValueError("题目未完成或答案已变化，请重新读取后复核")
        if request.citations_supported is not None and not is_fact_answer(case):
            raise ValueError("纯拒答或未完整结束的回答不计入事实引用支持率")
        case.review = QaEvalReview(
            correct=request.correct,
            citations_supported=request.citations_supported,
            note=request.note or "",
        )
        self._save_report(run)
        return self.view(request.run_id, owner)

    async def judge(self, request: QaEvalJudgeRequest, owner: int) -> QaEvalSnapshot:
        run = self.get(request.run_id, owner)
        if run.snapshot.busy:
            raise ValueError("当前题正在执行或复核，请等待结束")
        case = next((c for c in run.snapshot.cases if c.case_id == request.case_id), None)
        if case is None or case.status != "completed" or case.answer_hash != request.answer_hash:
            raise ValueError("题目未完成或答案已变化，请重新读取后复核")
        if not run.corpus_hash:
            raise ValueError("本报告只保存了结果，可继续人工复核；重新模型复核请新开一轮")
        run.snapshot.busy = True
        try:
            current_chunks = await run_in_threadpool(corpus.cached_corpus)
            dataset = EvalDataset.load(default_dataset_path(run.snapshot.dataset))
            validate_sources(
                default_dataset_path(run.snapshot.dataset),
                [item for item in dataset.cases if item.case_id == case.case_id],
                current_chunks,
            )
            if run.corpus_hash != corpus_fingerprint(current_chunks):
                raise ValueError("公开语料已变化，请重新运行问答后复核")
            model = (await run_in_threadpool(runtime.registry)).decision_model()
            fingerprint = model.config.fingerprint()
            if run.judge_hash is not None and run.judge_hash != fingerprint:
                raise ValueError("本轮决策模型配置已变化，请重新开始，避免混合模型判定")
            review = await judge_case(case, model)
            case.model_review = review
            run.judge_hash = fingerprint
        finally:
            run.snapshot.busy = False
            run.touched = time.monotonic()
            self._save_report(run)
        return self.view(request.run_id, owner, review.usage)
