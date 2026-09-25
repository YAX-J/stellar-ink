"""写作风格画像契约（E1）。

画像本身是 Python 算出来的统计量（见 `app/rag/style.py`），这里是它的跨语言形状：
Java 只需转发给前端展示与 Copilot 使用，**不参与计算、不做加工** ——
一旦 Java 侧开始「补一个字段」或「四舍五入一下」，前端看到的就不是画像的真实口径了。
"""

from typing import Annotated

from pydantic import Field

from app.schemas.base import ContractRequest, ContractResponse

#: 一次画像最多分析多少篇：越多越准，但每篇都要读正文
MAX_STYLE_SAMPLES = 50


class WritingStyleProfile(ContractResponse):
    """量出来的写作习惯。

    **不含任何原句**：`common_phrases` 只放反复出现（≥3 次）的字组。
    这条约束是刻意的 —— 画像会进提示词，粘一句作者的原话进去，下一轮模型就会照抄。
    """

    sample_count: int = Field(ge=0, description="参与统计的篇数")
    char_count: int = Field(ge=0, description="总字数（中日韩按字 + 拉丁按词）")
    paragraph_count: int = Field(ge=0, description="段落数（按非空行计）")
    sentence_count: int = Field(ge=0, description="句子数")

    median_sentence_chars: float = Field(ge=0, description="句长中位数（比平均数稳）")
    min_sentence_chars: int = Field(ge=0, description="最短句长")
    max_sentence_chars: int = Field(ge=0, description="最长句长")
    short_sentence_ratio: float = Field(ge=0, le=1, description="短句（≤15 字）占比")

    clauses_per_100_chars: float = Field(ge=0, description="逗号/顿号密度（次/百字）")
    question_ratio: float = Field(ge=0, le=1, description="问句占比")
    informal_mark_ratio: float = Field(ge=0, le=1, description="破折号/省略号密度")

    common_phrases: list[str] = Field(
        default_factory=list, description="反复出现的字组（≥3 次），不含原句"
    )
    transitions: list[str] = Field(default_factory=list, description="常用关联词")
    top_tags: list[str] = Field(default_factory=list, description="最常用的标签")


class WritingStyleRequest(ContractRequest):
    """``POST /writing/style`` 的请求体。"""

    author_id: Annotated[int, Field(gt=0, description="要画像的作者 id（由 Java 从登录身份传入）")]

    max_samples: Annotated[
        int,
        Field(default=20, ge=1, le=MAX_STYLE_SAMPLES, description="最多分析多少篇"),
    ] = 20


class WritingStyleResult(ContractResponse):
    """画像结果。

    `evidenceSufficient=false` 表示**样本不够**（作者还没写够）：此时 `profile` 为空，
    前端要显示「样本还不够」而不是画一堆 0 —— 0 与「没量」是两件事。
    """

    author_id: int = Field(gt=0, description="回显作者 id")
    evidence_sufficient: bool = Field(description="样本是否够量出画像")
    profile: WritingStyleProfile | None = Field(default=None, description="画像；样本不足时为 null")
    notes: str = Field(default="", description="口径说明与样本不足的可读原因")
