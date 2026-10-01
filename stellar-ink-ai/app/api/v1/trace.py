"""按 traceId 回放链路（E3-4，仅内网可达）。

一个 traceId 就能看到「检索做了什么、调了哪些工具、模型花了多少 token、哪一步失败了」，
而不用把三处日志按时间戳手工拼起来 —— 这正是 M8/M7→M8 决策门里「每个 traceId 可回放」那条。

**没有新基础设施**：数据来自 `app/core/trace.py` 的进程内缓冲（有界、只存结构不存内容）。
跨副本或超出缓冲范围的查询会返回 `found=false` —— 那不是「链路不存在」，
而是「这一台没有它的记录」。要真正跨副本就得集中存储（OTel / Langfuse），
而那是需要单独拍板的部署决定（见 docs/ai/status.md §4.5）。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends

from app.core.internal_auth import InternalIdentity
from app.core.internal_auth_middleware import require_internal_identity
from app.core.trace import trace_snapshot

logger = logging.getLogger(__name__)

router = APIRouter(tags=["trace"])


@router.get("/internal/trace/{trace_id}", summary="按 traceId 回放链路（仅内网）")
async def trace(
    trace_id: str,
    identity: InternalIdentity = Depends(require_internal_identity),  # noqa: B008 - 见 app/main.py
) -> dict[str, object]:
    """返回该 traceId 的结构化事件。

    刻意**不返回 404**：查不到有两种完全不同的原因 —— 缓冲已淘汰、或这条链路落在别的副本上，
    而两者都不代表「这条链路不存在」。返回 `found=false` + 空列表，让调用方自己决定怎么显示。
    """
    snapshot = trace_snapshot(trace_id)
    logger.info(
        "trace 回放：traceId=%s found=%s events=%d byUser=%s",
        trace_id,
        snapshot is not None,
        len(snapshot["events"]) if snapshot else 0,
        identity.user_id,
    )
    if snapshot is None:
        return {"traceId": trace_id, "found": False, "events": []}
    return {"traceId": snapshot["traceId"], "found": True, "events": snapshot["events"]}
