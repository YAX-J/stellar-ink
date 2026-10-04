"""星海问答接口（仅内网可达，由 Java `ai-service` 转发给前端）。

非流式 `POST /qa` 与流式 `POST /qa/stream` 共用同一套编排：检索 → 引用 → 提示词 →
模型 → 拒答。两者差别只在「怎么把答案交出去」，不在「答案怎么来」。

模型与语料从哪来：`app/api/v1/assembly.py`。**模型只有面板一个来源**（没有代码里的默认值），
未配置时返回 400 并说清去配哪个角色；`usage.model` 会如实显示实际用的模型名，
配置成 fake 时前端据此提示「当前是离线自测」。
"""

import asyncio
import logging
from collections.abc import AsyncIterator

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse, StreamingResponse

from app.api.v1.assembly import ASSEMBLY_ERRORS, assembly_error, pipeline_for, roles_for
from app.core.internal_auth import InternalIdentity
from app.core.internal_auth_middleware import require_internal_identity
from app.providers import runtime
from app.rag.pipeline import RetrievalConfig
from app.rag.qa import QaService, QaSettings
from app.schemas.qa import QaAnswer, QaStreamRequest
from app.schemas.qa_stream import StreamEvent, heartbeat

logger = logging.getLogger(__name__)

router = APIRouter(tags=["qa"])

#: SSE 心跳间隔：模型第一个 token 可能要等十几秒，中间的代理会掐掉静默连接
HEARTBEAT_SECONDS = 10.0

#: 问答用的检索配置：混合召回（BM25 + 向量），**不设相似度下限**。
#:
#: 关于 `min_dense_score` 为什么是 0：给它设一个「看起来合理」的值（曾经是 0.2）会让
#: 向量通路**静默清空** —— 余弦量级对不对取决于嵌入模型，离线 Fake 的哈希伪向量只有 0.03 量级，
#: 于是混合检索退化成纯 BM25，而日志、指标、响应全都正常。
#: 这道闸门必须**接上真实嵌入模型后用评测台标定**（`scripts/calibrate_min_score.py`，
#: 以及 fast-track-plan §5 的分数重叠结论），不能凭感觉填。
QA_RETRIEVAL = RetrievalConfig(
    enable_sparse=True,
    enable_dense=True,
    candidate_k=20,
    min_dense_score=0.0,
    label="qa",
)


def build_qa_service(user_id: int | None = None) -> QaService:
    """按当前面板配置装配问答服务。

    **装配本身不做缓存**：语料与检索管道都由 `assembly` 按语料版本 + 配置指纹缓存，
    这里只是把已经预热好的管道和模型实例拼起来，代价可以忽略。
    反过来说，任何一层要是漏了缓存，接上真实嵌入模型后就会变成
    「每问一句把整库嵌入一遍」—— 那不是慢一点，是费用问题。

    `user_id` 只影响**生成用的模型**（个人配置，M12）：检索那条链路的
    embedding/rerank 始终取全局配置 —— 向量索引只有一份，换模型检索得到的是错的结果。
    """
    # 先一次性预检全部角色：缺 chat 又缺 embedding 时报两次，用户要跑两趟
    runtime.require_roles(*roles_for(QA_RETRIEVAL))
    runtime.require_roles_for(user_id, "chat")
    return QaService(
        pipeline=pipeline_for(QA_RETRIEVAL),
        chat=runtime.registry_for(user_id).chat_model(),
        settings=QaSettings(),
    )


@router.post("/qa", summary="星海问答（非流式）", response_model=None)
async def ask(
    request: QaStreamRequest,
    identity: InternalIdentity = Depends(require_internal_identity),  # noqa: B008 - 见 app/main.py
) -> QaAnswer | JSONResponse:
    """一次问答：检索 → 引用 → 提示词 → 模型 → 结论（证据不足时明确拒答）。"""
    try:
        service = build_qa_service(identity.user_id)
    except ASSEMBLY_ERRORS as error:
        return assembly_error(error)

    answer = await service.answer(request)
    logger.info(
        "qa answered: evidenceSufficient=%s citations=%d model=%s latencyMs=%s",
        answer.evidence_sufficient,
        len(answer.citations),
        answer.usage.model,
        answer.usage.latency_ms,
    )
    return answer


@router.post("/qa/stream", summary="星海问答（SSE 流式）", response_model=None)
async def ask_stream(
    request: QaStreamRequest,
    identity: InternalIdentity = Depends(require_internal_identity),  # noqa: B008 - 见 app/main.py
) -> StreamingResponse | JSONResponse:
    """流式问答：`meta → citation* → delta* → done`（见 `schemas/qa_stream.py`）。

    为什么用「生产者任务 + 队列」而不是直接 `async for event in service.stream(...)`：
    直连的话，模型第一个 token 之前是一段静默期，中间的反向代理会把没有数据的连接掐掉
    （表现为「答到一半突然断开」）。用队列就能在等事件时按间隔插心跳注释行。
    任务在生成器被关闭时一并取消 —— 浏览器断开 → ASGI 关闭生成器 → 队列任务取消 →
    上游 HTTP 流关闭。**这条链路是「关掉页面就停止烧 token」的全部实现**。
    """
    try:
        service = build_qa_service(identity.user_id)
    except ASSEMBLY_ERRORS as error:
        return assembly_error(error)

    return StreamingResponse(
        _sse_frames(service, request),
        media_type="text/event-stream",
        headers={
            # 关掉代理缓冲，否则「流式」会被攒成一整块再吐出来，前端看到的仍是转圈等到最后
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


async def _sse_frames(service: QaService, request: QaStreamRequest) -> AsyncIterator[str]:
    """把编排层的事件流转成 SSE 帧，并在静默期插心跳。"""
    queue: asyncio.Queue[StreamEvent | None] = asyncio.Queue()

    async def pump() -> None:
        try:
            async for event in service.stream(request):
                await queue.put(event)
        finally:
            # 用哨兵结束消费循环：不放的话 `queue.get()` 会永远等下去
            await queue.put(None)

    task = asyncio.create_task(pump())
    try:
        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=HEARTBEAT_SECONDS)
            except TimeoutError:
                yield heartbeat()
                continue
            if event is None:
                break
            yield event.to_frame()
    except asyncio.CancelledError:
        # 客户端断开：记一条日志便于确认「取消确实传到了下游」，然后原样抛出
        logger.info("问答流被客户端取消（下游生成一并终止）")
        raise
    finally:
        task.cancel()
