"""星笺 Copilot 契约（写作建议）。

边界（红线 §7.4）：建议只返回候选文本与理由，**不直接写正文**；
采纳与否由前端差异预览 + 作者确认决定，文章写入仍走既有 ``/posts/**``。
"""

from enum import StrEnum
from typing import Annotated

from pydantic import Field, model_validator

from app.schemas.base import ContractRequest, ContractResponse
from app.schemas.common import Usage

#: 单次请求允许携带的草稿长度（草稿只在本次请求内存中使用，不入索引）
MAX_DRAFT_LENGTH = 20_000

#: 单次返回的候选数量上限
MAX_CANDIDATES = 5


class WritingTask(StrEnum):
    """写作任务类型；与前端 Copilot 的功能一一对应。"""

    TITLE = "title"
    OUTLINE = "outline"
    CONTINUE = "continue"
    POLISH = "polish"
    TAGS = "tags"
    SUMMARY = "summary"


class WritingTone(StrEnum):
    """结构化写作目标，避免每次都用自然语言描述「想要什么风格」。"""

    KEEP = "keep"
    RESTRAINED = "restrained"
    COLLOQUIAL = "colloquial"
    CONCISE = "concise"


DraftText = Annotated[
    str,
    Field(max_length=MAX_DRAFT_LENGTH, description=f"当前草稿（不超过 {MAX_DRAFT_LENGTH} 字）"),
]

#: 没有草稿就无法工作的任务：缺草稿属于契约错误，而不是「返回空建议」
TASKS_REQUIRING_DRAFT = frozenset(
    {WritingTask.CONTINUE, WritingTask.POLISH, WritingTask.TAGS, WritingTask.SUMMARY}
)


class WritingSuggestRequest(ContractRequest):
    """``POST /ai/writing/suggest`` 的请求体。"""

    task: WritingTask

    draft: DraftText = Field(
        default="",
        description="当前草稿正文；草稿不外发、不入公共索引，仅在本次请求内使用",
    )

    instruction: str | None = Field(
        default=None,
        max_length=500,
        description="附加要求（可选），与 tone 同时存在时以 instruction 为准",
    )

    tone: WritingTone = Field(default=WritingTone.KEEP, description="结构化风格目标")

    candidate_count: int = Field(
        default=3,
        ge=1,
        le=MAX_CANDIDATES,
        description="期望的候选数量上限，实际可能更少",
    )

    @model_validator(mode="after")
    def _require_draft_for_content_tasks(self) -> "WritingSuggestRequest":
        if self.task in TASKS_REQUIRING_DRAFT and not self.draft.strip():
            raise ValueError(f"task={self.task.value} 需要非空 draft")
        return self


class WritingCandidate(ContractResponse):
    """单个候选：``text`` 是建议内容，``rationale`` 说明为什么这么改。"""

    text: str = Field(min_length=1, description="候选正文（标题/段落/标签等）")

    rationale: str | None = Field(default=None, description="面向作者的中文理由，可空")


class WritingSuggestResult(ContractResponse):
    """写作建议结果。M0 只定义契约，实现从 M5 起接入。"""

    task: WritingTask = Field(description="回显任务类型，便于前端分流渲染")

    candidates: list[WritingCandidate] = Field(
        default_factory=list,
        max_length=MAX_CANDIDATES,
        description=f"候选列表，最多 {MAX_CANDIDATES} 条",
    )

    usage: Usage = Field(default_factory=Usage, description="用量与耗时")
