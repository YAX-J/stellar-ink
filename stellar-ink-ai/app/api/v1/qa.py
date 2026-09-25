"""星海问答接口（仅内网可达，由 Java `ai-service` 转发给前端）。

当前是**非流式**版本：一次请求拿完整答案。流式（`/qa/stream` 的 SSE 事件）等 Java 侧
协议转换与前端消费方一起做 —— 先造一条没人用的流式通道是本仓库明确避免的事
（见 `development-workflow.md` §9）。

语料与模型从哪来：与评测台同一套 —— 语料取自种子内容包，模型用 `FakeProvider`。
**这不是最终形态**：真实问答要在索引建好之后走 Qdrant、模型用面板里配的 Provider。
在那之前这个接口的价值是「把检索 → 引用 → 提示词 → 拒答这条编排跑通且可测」，
响应里的 `usage.model` 会如实显示 `fake`，前端据此提示「当前是离线自测」。

关于缓存：装配（读语料 + 切块 + 建 BM25 + 预计算向量）必须**只做一次**。
每次请求重建的话，接上真嵌入模型后会变成「每问一句就把整库嵌入一遍」——
那不是慢一点的问题，是费用问题。
"""

import asyncio
import logging
from collections.abc import AsyncIterator
from functools import lru_cache

from fastapi import APIRouter
from fastapi.responses import JSONResponse, StreamingResponse

from app.providers.config_source import (
    ProviderConfigError,
    describe_sources,
    load_provider_configs,
)
from app.providers.errors import ProviderError
from app.providers.models import ProviderConfig
from app.providers.resolver import ProviderResolver
from app.rag.pipeline import IndexedChunk, RetrievalConfig, RetrievalPipeline, build_corpus
from app.rag.qa import QaService, QaSettings
from app.rag.seed_corpus import load_seed_posts
from app.schemas.common import AiErrorCode
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

#: 配置来源：**只从面板配置**（ai_provider_config / 环境变量注入），没有代码里的默认模型。
#: 未配置时 `registry.chat_model()` 会抛「请在 AI 实验室 → 模型配置里填写」，
#: 由端点转成可读的错误 —— 而不是退回 Fake 让「没配好」表现成「回答质量差」。
resolver = ProviderResolver(source=load_provider_configs)


def resolve_corpus() -> list[IndexedChunk]:
    """语料：种子内容包切块（真实形态是索引/内容服务推过来，见 status.md §4.1）。"""
    corpus = build_corpus(load_seed_posts())
    if not corpus:
        raise ValueError("语料为空：问答没有可检索的内容")
    return corpus


@lru_cache(maxsize=1)
def _cached_corpus() -> list[IndexedChunk]:
    return resolve_corpus()


def reset_assembly() -> None:
    """丢掉装配缓存（测试与「改了配置想立刻生效」时用）。

    一次性清两处：语料缓存与 Provider 解析器。**分开清最容易漏一处** ——
    漏了语料缓存会让人以为「改语料没生效」，漏了解析器会以为「改模型没生效」。
    """
    _cached_corpus.cache_clear()
    resolver.invalidate()


def use_provider_configs(configs: list[ProviderConfig]) -> None:
    """把解析器固定到给定配置（测试与离线脚本用）。

    注意它**不会**替你造一个默认模型：调用方必须显式给出配置，
    包括「我要用 fake」也要显式写成 `provider="fake"` ——
    这正是面板里把协议选成 Fake 的等价物。
    """
    global resolver  # noqa: PLW0603 - 有意提供这个接缝：装配来源只在启动/测试时确定
    resolver = ProviderResolver(source=lambda: tuple(configs))
    reset_assembly()


def build_qa_service() -> QaService:
    """按当前配置装配问答服务。

    语料切块很贵（要建 BM25 索引、预计算向量），所以缓存；
    **模型实例不在这里缓存** —— 它们由 `ProviderResolver` 按配置指纹管，
    换模型立刻生效，而语料该不该重建由「语料变没变」决定，两者不该绑在一起。
    """
    registry = resolver.registry()
    pipeline = RetrievalPipeline(
        corpus=_cached_corpus(),
        config=QA_RETRIEVAL,
        embedder=registry.embedding_model(),
    )
    return QaService(pipeline=pipeline, chat=registry.chat_model(), settings=QaSettings())


def assembly_error(error: Exception) -> JSONResponse:
    """把「装配不起来」翻译成可读的响应。

    两种情况都是**配置/环境问题**，不该伪装成 500 让人去翻栈：
    - 语料缺失（`ValueError`）；
    - 模型角色没配（`UnsupportedCapabilityError` / `ProviderError`）或配置读不出来
      （`ProviderConfigError`）—— 这类必须说清「去面板配哪个角色」，
      因为最常见的误判是「服务坏了」，而实际只是没填。

    「角色尚未配置」还会附上 `describe_sources()`：空配置有两个完全不同的原因
    （真没配 / MYSQL_* 没给齐导致读不到），混成一句话会把排查方向带偏。
    """
    message = str(error)
    if "尚未配置" in message:
        message = f"{message}；{describe_sources()}"
    return JSONResponse(
        status_code=400,
        content={"code": AiErrorCode.BAD_REQUEST.value, "message": message},
    )


@router.post("/qa", summary="星海问答（非流式）", response_model=None)
async def ask(request: QaStreamRequest) -> QaAnswer | JSONResponse:
    """一次问答：检索 → 引用 → 提示词 → 模型 → 结论（证据不足时明确拒答）。"""
    try:
        service = build_qa_service()
    except (ValueError, ProviderError, ProviderConfigError) as error:
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
async def ask_stream(request: QaStreamRequest) -> StreamingResponse | JSONResponse:
    """流式问答：`meta → citation* → delta* → done`（见 `schemas/qa_stream.py`）。

    为什么用「生产者任务 + 队列」而不是直接 `async for event in service.stream(...)`：
    直连的话，模型第一个 token 之前是一段静默期，中间的反向代理会把没有数据的连接掐掉
    （表现为「答到一半突然断开」）。用队列就能在等事件时按间隔插心跳注释行。
    任务在生成器被关闭时一并取消 —— 浏览器断开 → ASGI 关闭生成器 → 队列任务取消 →
    上游 HTTP 流关闭。**这条链路是「关掉页面就停止烧 token」的全部实现**。
    """
    try:
        service = build_qa_service()
    except (ValueError, ProviderError, ProviderConfigError) as error:
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
