"""作者记忆的端点（M9）：抽取候选、算写入计划、算召回集合。

**这里一条都不落库**：记忆的持久化归 Java（`ai_memory` 系列表），Python 不碰库 ——
与 E4 的 Wiki 同一条边界。所以这一层的产物是「**判断**」：
哪些候选够格（抽取）、哪些该写进去（计划）、哪些能被召回（过滤）。
真正写库、状态流转（禁用/删除/清除）与用户界面都在 Java 与前端那侧。

两条刻意的设计：

* 端点里**没有 `userId` 参数**：身份只从签名的 `X-AI-*` 头来（Java 传）。
  「替我查用户 X 的记忆」这种接口一旦存在，客户端构造参数就能越权。
* **出处校验只做一次，就在抽取那一步**。计划与召回这两层拿不到上下文，
  所以它们**不重新校验出处**（重新校验只能是走过场）—— 也不假装校验。
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.api.v1.assembly import ASSEMBLY_ERRORS, assembly_error
from app.providers import runtime
from app.rag.memory import (
    MemoryCandidate,
    MemoryEvidence,
    MemoryRecord,
    extract_candidates_async,
    filter_for_recall,
    plan_writes,
)
from app.schemas.memory import (
    MemoryCandidateView,
    MemoryConflictView,
    MemoryDuplicateView,
    MemoryEvidenceView,
    MemoryExtractRequest,
    MemoryExtractResult,
    MemoryExtractStatsView,
    MemoryPlanRequest,
    MemoryPlanResult,
    MemoryRecallRequest,
    MemoryRecallResult,
    MemoryRecordView,
)

logger = logging.getLogger(__name__)

router = APIRouter(tags=["memory"])


def _candidate_view(candidate: MemoryCandidate) -> MemoryCandidateView:
    """`MemoryCandidate` → 契约视图（证据一起带上）。"""
    return MemoryCandidateView(
        type=candidate.memory_type,
        content=candidate.content,
        confidence=candidate.confidence,
        source=candidate.source,
        evidence=[
            MemoryEvidenceView(kind=item.kind, ref=item.ref, post_id=item.post_id)
            for item in candidate.evidence
        ],
    )


def _candidate_of(view: MemoryCandidateView) -> MemoryCandidate:
    """契约视图 → 域对象。

    ⚠️ 这里**不做出处校验**（见模块 docstring）：计划的输入是「抽取阶段已经校验过的候选」，
    而计划这一层手里没有上下文，能做的只有「原样接受」。
    曾经想用「拼一个假上下文再校验一次」来复用校验函数 —— 那是**走过场的校验**，
    比不校验更糟：它会让读代码的人以为这一层也守着出处这道门。
    """
    return MemoryCandidate(
        memory_type=view.type,
        content=view.content,
        evidence=tuple(
            MemoryEvidence(kind=item.kind, ref=item.ref, post_id=item.post_id)
            for item in view.evidence
        ),
        confidence=view.confidence,
        source=view.source,
    )


def _record(view: MemoryRecordView) -> MemoryRecord:
    """契约视图 → 已有记忆。

    `user_id` 固定 0：用户隔离由 Java 的取数范围保证（见 `/memory/recall` 的说明）。
    """
    return MemoryRecord(
        memory_id=view.memory_id,
        user_id=0,
        memory_type=view.type,
        content=view.content,
        confidence=view.confidence,
        status=view.status,
        evidence=tuple(
            MemoryEvidence(kind=item.kind, ref=item.ref, post_id=item.post_id)
            for item in view.evidence
        ),
    )


@router.post("/memory/candidates", summary="从对话里抽取记忆候选（M9）", response_model=None)
async def candidates(request: MemoryExtractRequest) -> MemoryExtractResult | JSONResponse:
    """抽候选并**立刻用规则校验**：出处编造、类型不符、敏感信息一律丢弃并计数。

    丢弃原因分类计数很重要：记忆抽得少时，要能一眼分清是「模型没提出」还是
    「提出了但出处对不上」—— 后者的处置是改提示词或换模型，完全不同。
    """
    try:
        runtime.require_roles("chat")
        chat = runtime.registry().chat_model()
    except ASSEMBLY_ERRORS as error:
        return assembly_error(error)

    outcome = await extract_candidates_async(
        request.conversation,
        chat,
        max_candidates=request.max_candidates,
        source=request.source,
    )
    kept = len(outcome.candidates)
    notes = list(outcome.notes)
    if outcome.dropped.get("noEvidence"):
        notes.append(
            f"{outcome.dropped['noEvidence']} 条候选的出处在这段对话里找不到，已丢弃 —— "
            "记忆没有出处就会被当成「用户的事实」，这是最不能放的一类。"
        )
    if outcome.dropped.get("sensitive"):
        notes.append(
            f"{outcome.dropped['sensitive']} 条候选含敏感信息"
            "（手机号/邮箱/证件号/密钥等），已丢弃。"
        )
    logger.info(
        "记忆候选抽取：提出 %d 条，留下 %d 条，丢弃 %s；模型 %s",
        kept + sum(outcome.dropped.values()),
        kept,
        dict(outcome.dropped) or "无",
        outcome.usage_model,
    )
    return MemoryExtractResult(
        candidates=[_candidate_view(item) for item in outcome.candidates],
        stats=MemoryExtractStatsView(
            proposed=kept + sum(outcome.dropped.values()),
            kept=kept,
            dropped=dict(outcome.dropped),
        ),
        notes=notes,
        usage_model=outcome.usage_model,
    )


@router.post("/memory/plan", summary="算写入计划：新增/重复/冲突（M9）", response_model=None)
async def plan(request: MemoryPlanRequest) -> MemoryPlanResult:
    """把候选分成三份清单。**冲突不自动覆盖** —— 这一层只报告，不决定。"""
    plan_result = plan_writes(
        [_record(item) for item in request.existing],
        [_candidate_of(item) for item in request.candidates],
    )
    enriched_ids = {memory_id for memory_id, _ in plan_result.enriched}
    return MemoryPlanResult(
        to_add=[_candidate_view(item) for item in plan_result.to_add],
        duplicates=[
            MemoryDuplicateView(
                memory_id=memory_id,
                content=candidate.content,
                enriched=memory_id in enriched_ids,
            )
            for memory_id, candidate in plan_result.duplicates
        ],
        conflicts=[
            MemoryConflictView(
                memory_id=record.memory_id,
                existing_content=record.content,
                candidate_content=candidate.content,
            )
            for record, candidate in plan_result.conflicts
        ],
        notes=plan_result.notes,
    )


@router.post("/memory/recall", summary="算可召回的记忆 id（M9）", response_model=None)
async def recall(request: MemoryRecallRequest) -> MemoryRecallResult:
    """按类型 / 可信度 / 有效期过滤，排序确定。

    ⚠️ **用户隔离不在这里做**：`user_id` 不在请求体里（身份只由 Java 传），
    Java 按登录身份取出该用户的记忆再调这里 —— 于是「A 的记忆不会被 B 召回」
    由**取数范围**保证。这一层再叠一层只会让人误以为它是安全边界，
    而真正的边界在「谁能拿到哪批数据」。
    """
    now = datetime.now(UTC)
    expiry: dict[int, datetime | None] = {
        memory_id: datetime.fromtimestamp(stamp / 1000, tz=UTC)
        for memory_id, stamp in request.expires_at_ms.items()
    }
    kept = filter_for_recall(
        [_record(item) for item in request.memories],
        user_id=0,
        now=now,
        types=request.types or None,
        min_confidence=request.min_confidence,
        limit=request.limit,
        expires_at=expiry,
    )
    notes: list[str] = []
    if not kept and request.memories:
        notes.append("按当前条件没有可召回的记忆（不是故障）。")
    return MemoryRecallResult(memory_ids=[item.memory_id for item in kept], notes=notes)
