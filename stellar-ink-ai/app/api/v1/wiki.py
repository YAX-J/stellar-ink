"""LLM Wiki 的端点（E4-1：带证据的主张抽取）。

内部签名保护（不在公开白名单里）。这一版**只回结果、不落库**：
主张的持久化归 Java（`ai_wiki_*` 表），Python 不碰库 —— 与其它 AI 能力的边界一致。
落库与读者侧页面是后续切片（见 `docs/ai/fast-track-plan.md` 的 E4 拆分）。
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.api.v1.assembly import ASSEMBLY_ERRORS, assembly_error
from app.providers import runtime
from app.rag.corpus import cached_corpus
from app.rag.wiki import ExtractionResult, extract_claims_async
from app.schemas.wiki import (
    WikiClaimsRequest,
    WikiClaimsResult,
    WikiClaimView,
    WikiEntityMentionView,
    WikiEntityView,
    WikiExtractionStatsView,
)

logger = logging.getLogger(__name__)

router = APIRouter(tags=["wiki"])


@router.post("/wiki/claims", summary="抽取带证据的主张（E4-1）", response_model=None)
async def claims(request: WikiClaimsRequest) -> WikiClaimsResult | JSONResponse:
    """按文章抽取原子主张，并**逐条校验引用是否真的出现在原文里**。

    校验不通过就丢弃，且丢弃原因分类计数（`stats.dropped`）——
    静默丢弃会让「抽出来很少」看起来像模型不行，而真相往往是引用编造被挡掉了。
    """
    try:
        # 预检角色：没配 chat 模型时要给「去哪儿配」的可操作提示，而不是让抽取跑出 0 条
        runtime.require_roles("chat")
        chat = runtime.registry().chat_model()
    except ASSEMBLY_ERRORS as error:
        return assembly_error(error)

    result: ExtractionResult = await extract_claims_async(
        cached_corpus(),
        chat,
        max_posts=request.max_posts,
        max_claims_per_chunk=request.max_claims_per_chunk,
    )
    logger.info(
        "Wiki 主张抽取：文章 %d 篇，提出 %d 条，留下 %d 条，丢弃 %s；实体 提出 %d、留下 %d、"
        "合并成 %d；模型 %s",
        result.stats.posts,
        result.stats.proposed,
        result.stats.kept,
        result.stats.dropped or "无",
        result.stats.entity_proposed,
        result.stats.entity_kept,
        result.stats.entities,
        result.usage_model,
    )
    return WikiClaimsResult(
        claims=[WikiClaimView(**claim.to_dict()) for claim in result.claims],
        entities=[_entity_view(cluster) for cluster in result.entities],
        stats=WikiExtractionStatsView(**result.stats.to_dict()),
        notes=list(result.notes),
        usage_model=result.usage_model,
        latency_ms=result.latency_ms,
    )


def _entity_view(cluster: Any) -> WikiEntityView:
    """把实体簇转成契约视图（每个提及的 `mention.to_dict()` 与契约字段名一致）。"""
    return WikiEntityView(
        name=cluster.name,
        normalized=cluster.normalized,
        kind=cluster.kind,
        count=cluster.count,
        post_ids=sorted({mention.post_id for mention in cluster.mentions}),
        mentions=[WikiEntityMentionView(**mention.to_dict()) for mention in cluster.mentions],
    )
