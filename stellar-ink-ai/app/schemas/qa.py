"""星海问答契约（M3 起接入检索，M5 起接前端 SSE）。

只问**已发布**文章：请求里不允许指定任意数据范围，
权限与可见性由 Java ``ai-service`` 决定后经 HMAC 头传入（M1）。
"""

from typing import Annotated

from pydantic import Field, field_validator

from app.schemas.base import ContractRequest, ContractResponse
from app.schemas.common import (
    MAX_QUESTION_LENGTH,
    Citation,
    DoneReason,
    Usage,
)

#: 单次问答最多返回的引用数
MAX_CITATIONS = 8

QuestionText = Annotated[
    str,
    Field(
        min_length=1,
        max_length=MAX_QUESTION_LENGTH,
        description=f"用户问题（不超过 {MAX_QUESTION_LENGTH} 字）",
    ),
]

CitationList = Annotated[list[Citation], Field(max_length=MAX_CITATIONS)]


class QaStreamRequest(ContractRequest):
    """``POST /ai/qa/stream`` 的请求体。"""

    question: QuestionText

    conversation_id: str | None = Field(
        default=None,
        max_length=64,
        description="多轮会话标识；为空表示一次性提问（M0 不持久化）",
    )

    top_k: int = Field(
        default=5,
        ge=1,
        le=20,
        description="召回候选数上限；实际取值还会受服务端预算限制",
    )

    @field_validator("question")
    @classmethod
    def _reject_blank_question(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("question 不能为空白字符串")
        return value


class QaAnswer(ContractResponse):
    """非流式问答结果；流式场景下由 SSE ``done`` 事件携带的关键字段等价于此。"""

    answer: str = Field(description="答案正文；证据不足时给明确拒答说明")

    citations: CitationList = Field(
        default_factory=list,
        description=f"引用列表，按相关性排序，最多 {MAX_CITATIONS} 条",
    )

    done_reason: DoneReason = Field(default=DoneReason.STOP, description="结束原因")

    usage: Usage = Field(default_factory=Usage, description="用量与耗时")

    evidence_sufficient: bool = Field(
        default=True,
        description="证据是否充分；为 false 时 UI 应展示「文章中没有找到依据」",
    )
