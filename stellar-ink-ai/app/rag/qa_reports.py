"""按签名用户保存最近一轮完整报告；路径只由服务端配置，不接受客户端文件名。"""

from __future__ import annotations

import os
import uuid
from datetime import UTC, datetime
from pathlib import Path

from pydantic import BaseModel, ConfigDict

from app.core.config import PROJECT_ROOT
from app.schemas.qa_eval import QaEvalSnapshot

MAX_REPORT_BYTES = 5 * 1024 * 1024


class SavedQaReport(BaseModel):
    model_config = ConfigDict(extra="forbid")
    owner: int
    snapshot: QaEvalSnapshot
    corpus_hash: str = ""
    model_hash: str = ""
    judge_hash: str | None = None


class QaReportStore:
    def __init__(self, directory: Path) -> None:
        self.directory = directory

    @classmethod
    def from_env(cls) -> QaReportStore:
        path = Path(os.environ.get("AI_QA_REPORT_DIR", "data/qa-eval-reports"))
        return cls(path if path.is_absolute() else PROJECT_ROOT / path)

    def _path(self, owner: int) -> Path:
        if owner < 0:
            raise ValueError("报告身份不合法")
        return self.directory / f"qa-eval-{owner}.json"

    def load(self, owner: int) -> SavedQaReport | None:
        path = self._path(owner)
        try:
            if not path.exists():
                return None
            if path.stat().st_size > MAX_REPORT_BYTES:
                raise ValueError("报告过大")
            saved = SavedQaReport.model_validate_json(path.read_text(encoding="utf-8"))
            if saved.owner != owner:
                raise ValueError("报告身份不匹配")
            self._validate(saved.snapshot)
            return saved
        except (OSError, ValueError) as error:
            raise ValueError("最近报告暂时无法读取，请检查存档或重新执行评测") from error

    @staticmethod
    def _validate(snapshot: QaEvalSnapshot) -> None:
        if (not 0 < snapshot.total <= 30 or snapshot.completed != snapshot.total
                or len(snapshot.cases) != snapshot.total or snapshot.busy
                or len({c.case_id for c in snapshot.cases}) != snapshot.total
                or any(c.status not in {"completed", "error"}
                       or (c.status == "completed" and c.answer is None)
                       for c in snapshot.cases)):
            raise ValueError("只能保存完整执行结束的报告")

    def save(self, saved: SavedQaReport) -> datetime:
        self._validate(saved.snapshot)
        timestamp = datetime.now(UTC)
        saved = saved.model_copy(deep=True)
        saved.snapshot.report_saved_at = timestamp
        saved.snapshot.report_error = ""
        saved.snapshot.step_usage = None
        saved.snapshot.execution_available = bool(saved.corpus_hash and saved.model_hash)
        raw = saved.model_dump_json(indent=2, by_alias=True)
        if len(raw.encode("utf-8")) > MAX_REPORT_BYTES:
            raise ValueError("报告过大，请导出后自行保存")
        target = self._path(saved.owner)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(f".{uuid.uuid4().hex}.tmp")
        try:
            temporary.write_text(raw, encoding="utf-8")
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
        return timestamp
