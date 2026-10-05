"""星海问答契约（M3 起接入检索，M5 起接前端 SSE）。

只问**已发布**文章：请求里不允许指定任意数据范围，
权限与可见性由 Java ``ai-service`` 决定后经 HMAC 头传入（M1）。
"""

from typing import Annotated

from pydantic import Field, field_validator

from app.schemas.base import ContractModel, ContractRequest, ContractResponse
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

#: 一次问答最多带几条长期记忆进提示词（M9）。
#: 上限存在的理由与 context 预算一样：记忆是**语气与取舍**的参考，不是内容来源，
#: 堆多了会挤掉真正要引用的摘录。
MAX_MEMORIES = 5

#: 一次问答最多带几轮历史进提示词（多轮会话）。
#: 与记忆同理：历史是**语境的参考**（用来理解「那它呢」「上面那个报错」指什么），
#: 不是内容来源。堆多了同样是挤掉摘录，而不是让回答更准。
MAX_HISTORY_TURNS = 6

#: 单轮历史里回答的字符上限：前端只回送自己渲染过的那一份，这里再收一道
MAX_HISTORY_ANSWER = 2000


class HistoryTurn(ContractModel):
    """多轮会话里的一轮问答。

    ⚠️ **它不是证据**：提示词里明确要求只用它理解「他在追问什么」，
    不得当事实陈述、更不得据它编号引用（与 M9 记忆同一口径）。
    """

    question: QuestionText
    answer: str = Field(
        min_length=1,
        max_length=MAX_HISTORY_ANSWER,
        description="上一轮的回答（前端原样回送，供模型理解追问里的指代）",
    )


class QaStreamRequest(ContractRequest):
    """``POST /ai/qa/stream`` 的请求体。"""

    question: QuestionText

    memories: list[str] = Field(
        default_factory=list,
        max_length=MAX_MEMORIES,
        description="这位作者的长期记忆（M9，由 Java 按登录身份取好并过滤后传入）。"
        "**它不是文章内容**：提示词里明确要求只用它调整语气与取舍，"
        "不得当事实陈述、不得编号引用 —— 否则「作者喜欢短句」会被写成「文章里说他喜欢短句」",
    )

    history: list[HistoryTurn] = Field(
        default_factory=list,
        max_length=MAX_HISTORY_TURNS,
        description="最近几轮问答（**不是证据**，只用来理解追问里的指代）。"
        "为空表示一次性提问 —— 单轮问答（深读页的「问星笺」）不带它，"
        "而助手浮层会把本次会话的前几轮带上。",
    )

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
