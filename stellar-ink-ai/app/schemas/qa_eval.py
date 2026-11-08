"""完整问答评测契约：执行、自动检查、人工复核分别表达。"""

from datetime import datetime
from typing import Literal

from pydantic import Field

from app.schemas.base import ContractRequest, ContractResponse
from app.schemas.common import Usage
from app.schemas.qa import QaAnswer


class QaEvalStartRequest(ContractRequest):
    dataset: str = Field(min_length=1, max_length=64)
    max_cases: int | None = Field(default=None, ge=1, le=30)


class QaEvalRunRequest(ContractRequest):
    run_id: str = Field(min_length=1, max_length=64)


class QaEvalReviewRequest(QaEvalRunRequest):
    case_id: str = Field(min_length=1, max_length=64)
    answer_hash: str = Field(min_length=64, max_length=64)
    correct: bool | None = None
    citations_supported: bool | None = None
    note: str | None = Field(default=None, max_length=1000)


class QaEvalJudgeRequest(QaEvalRunRequest):
    case_id: str = Field(min_length=1, max_length=64)
    answer_hash: str = Field(min_length=64, max_length=64)


class QaEvalDecision(ContractResponse):
    choice: Literal["pass", "fail", "uncertain"]
    probabilities: dict[str, float]


class QaEvalModelReview(ContractResponse):
    model: str
    answer_hash: str
    rubric_version: str = "qa-judge-v1"
    threshold: float = 0.8
    decisions: dict[str, QaEvalDecision]
    correct: bool | None = None
    citations_supported: bool | None = None
    needs_human_review: bool
    usage: Usage


class QaEvalModelSummary(ContractResponse):
    reviewed: int = 0
    conclusive: int = 0
    passed: int = 0
    answer_pass_rate: float | None = None
    support_reviewed: int = 0
    support_passed: int = 0
    citation_pass_rate: float | None = None
    needs_human: int = 0
    total_tokens: int = 0


class QaEvalReview(ContractResponse):
    correct: bool | None = None
    citations_supported: bool | None = None
    note: str = ""


class QaEvalCheck(ContractResponse):
    checked: int = 0
    source_matched: int = 0
    missing_sources: int = 0
    out_of_range: list[int] = Field(default_factory=list)
    uncited: bool = False


class QaEvalCase(ContractResponse):
    case_id: str
    question: str
    expected_post_ids: list[int]
    expected_answer: str | None = None
    status: Literal["pending", "running", "completed", "error"] = "pending"
    answer: QaAnswer | None = None
    answer_hash: str = ""
    automatic: QaEvalCheck | None = None
    review: QaEvalReview = Field(default_factory=QaEvalReview)
    model_review: QaEvalModelReview | None = None
    error: str = ""
    trace_id: str = ""


class QaEvalSummary(ContractResponse):
    errors: int = 0
    total_tokens: int = 0
    returned_citations: int = 0
    source_matched: int = 0
    source_match_rate: float | None = None
    out_of_range: int = 0
    uncited_answers: int = 0
    uncited_refusals: int = 0
    unanswerable_completed: int = 0
    unanswerable_refused: int = 0
    false_refusals: int = 0
    truncated: int = 0
    correct_reviewed: int = 0
    correct_passed: int = 0
    answer_accuracy: float | None = None
    support_reviewed: int = 0
    support_passed: int = 0
    citation_support_rate: float | None = None


class QaEvalSnapshot(ContractResponse):
    run_id: str
    dataset: str
    dataset_label: str
    total: int
    completed: int
    busy: bool = False
    cases: list[QaEvalCase]
    summary: QaEvalSummary
    model_summary: QaEvalModelSummary = Field(default_factory=QaEvalModelSummary)
    step_usage: Usage | None = None
    report_saved_at: datetime | None = None
    report_error: str = ""
    execution_available: bool = True
