"""作者记忆的契约（M9）。

三条与别处不同的口径，都在字段上体现出来：

1. **用户身份不在请求体里**：`userId` 只从签名的 `X-AI-*` 头来（Java 传），
   Python 不接收「替我查用户 X 的记忆」这种参数 —— 否则客户端构造参数就能越权。
   所以这里的请求体里**没有** `userId` 字段。
2. **候选与记忆是两种东西**：`MemoryCandidateView`（还没落库）与 `MemoryRecordView`
   （库里已有）分开。混成一个结构会让「这条到底写没写进库」看不出来。
3. **冲突必须能解释**：`MemoryPlanView` 把「新增 / 重复 / 冲突」分成三份清单，
   前端要能对每一条说清「为什么没写进去」。
"""

from pydantic import Field

from app.rag.memory import MEMORY_TYPES, MIN_CONTENT_LENGTH
from app.schemas.base import ContractRequest, ContractResponse

#: 一次抽取的对话长度上限（当前对话只是**工作记忆**，roadmap M9 第 1 条要求限制上下文）
MAX_CONVERSATION_CHARS = 8000
#: 一次抽取最多产出多少候选
MAX_CANDIDATES = 10
#: 一次请求最多提交多少条候选去落库 / 参与规划
MAX_BATCH = 200


class MemoryEvidenceView(ContractResponse):
    """证据：回到某段原文，或用户自己确认过。"""

    kind: str = Field(description="quote（原文片段）/ user（用户确认）")
    ref: str = Field(description="原文片段或确认说明")
    post_id: int | None = Field(default=None, description="相关文章（可为空）")


class MemoryCandidateView(ContractResponse):
    """一条**候选**（还没落库）。"""

    type: str = Field(description="preference / fact / decision")
    content: str = Field(min_length=MIN_CONTENT_LENGTH, description="记忆正文")
    confidence: float = Field(ge=0, le=1, description="可信度（模型推测的封顶 0.7）")
    source: str = Field(description="model_suggested / user_stated / user_confirmed")
    evidence: list[MemoryEvidenceView] = Field(default_factory=list, description="出处（至少一条）")


class MemoryRecordView(ContractResponse):
    """库里已有的一条记忆（规划时的输入）。"""

    memory_id: int
    type: str
    content: str
    confidence: float = Field(ge=0, le=1)
    status: str = Field(description="pending / active / disabled / deleted")
    evidence: list[MemoryEvidenceView] = Field(default_factory=list)


class MemoryExtractRequest(ContractRequest):
    """``POST /memory/candidates``：从一段对话里抽候选。"""

    conversation: str = Field(
        min_length=1,
        max_length=MAX_CONVERSATION_CHARS,
        description="对话原文（当前对话只是工作记忆，长度受限）",
    )
    max_candidates: int = Field(default=5, ge=1, le=MAX_CANDIDATES)
    source: str = Field(
        default="model_suggested",
        description="model_suggested（模型推测）/ user_stated（用户自己说的）",
    )


class MemoryExtractStatsView(ContractResponse):
    """抽取的账：提了多少、留下多少、**按原因丢弃多少**。"""

    proposed: int = Field(ge=0, description="模型提出的条数")
    kept: int = Field(ge=0, description="通过校验的条数")
    dropped: dict[str, int] = Field(default_factory=dict, description="丢弃原因 → 条数")


class MemoryExtractResult(ContractResponse):
    candidates: list[MemoryCandidateView] = Field(default_factory=list)
    stats: MemoryExtractStatsView
    notes: list[str] = Field(default_factory=list)
    usage_model: str | None = Field(default=None, description="这次用的 chat 模型（如实回显）")


class MemoryPlanRequest(ContractRequest):
    """``POST /memory/plan``：拿已有记忆 + 候选，算出「新增 / 重复 / 冲突」。"""

    existing: list[MemoryRecordView] = Field(default_factory=list, max_length=MAX_BATCH)
    candidates: list[MemoryCandidateView] = Field(default_factory=list, max_length=MAX_BATCH)


class MemoryDuplicateView(ContractResponse):
    memory_id: int = Field(description="与哪条已有记忆重复")
    content: str
    enriched: bool = Field(
        default=False, description="是否带来了库里没有的新证据（true = 建议补证据，不改正文）"
    )


class MemoryConflictView(ContractResponse):
    memory_id: int = Field(description="与哪条已有记忆冲突")
    existing_content: str = Field(description="已有那条的正文（让用户看着两边决定）")
    candidate_content: str = Field(description="候选正文")


class MemoryPlanResult(ContractResponse):
    to_add: list[MemoryCandidateView] = Field(default_factory=list)
    duplicates: list[MemoryDuplicateView] = Field(default_factory=list)
    conflicts: list[MemoryConflictView] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class MemoryRecallRequest(ContractRequest):
    """``POST /memory/recall``：按用户/类型/可信度/有效期过滤出可召回的 id。"""

    memories: list[MemoryRecordView] = Field(default_factory=list, max_length=MAX_BATCH * 5)
    types: list[str] = Field(default_factory=list, description="为空 = 不限类型")
    min_confidence: float = Field(default=0.0, ge=0, le=1)
    limit: int = Field(default=10, ge=1, le=100)
    #: 过期时间只在**有值**时才拦；键是 memoryId（区分「没设过期」与「已过期」）
    expires_at_ms: dict[int, int] = Field(
        default_factory=dict, description="memoryId → 过期时间戳（毫秒，UTC）；缺键 = 不过期"
    )


class MemoryRecallResult(ContractResponse):
    memory_ids: list[int] = Field(
        default_factory=list, description="可召回的 id（按可信度与新旧排序）"
    )
    notes: list[str] = Field(default_factory=list)


def memory_types() -> tuple[str, ...]:
    """类型白名单（前端下拉与校验共用一处，避免两处各写一份）。"""
    return MEMORY_TYPES
