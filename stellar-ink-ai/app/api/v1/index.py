"""索引重建（M4 遗留缺口的补齐）：**真嵌真写**向量库。

⚠️ 路径必须与 Java 契约 `AiContractPaths.INDEX_REBUILD`（`/admin/index/rebuild`）**逐字一致**：
Feign 客户端没有 path 前缀，所以它请求的就是 Python 的这个路径；写错一个字就是 404，
而报出来的话会是「Python 不可用」，排查方向直接跑偏。

这条链路此前整段缺失：契约（schema/DTO/`AiContractPaths.INDEX_REBUILD`/Feign）齐全，
但 Python 没有这个路由、Java 没有 controller、`app/` 里也没有任何地方构造 `IndexPipeline` ——
于是「索引重建」这件事从来没有真正发生过
（`scripts/index_reconcile.py` 的提示还指过一个不存在的接口）。

三个刻意的口径：

1. **同步执行，不造假队列**：契约的返回体是 `IndexJob`（带 status 与进度）。我们没有任务队列，
   所以这里**跑完才返回**，status 直接是终态（SUCCEEDED / FAILED），进度如实填写。
   ⚠️ 因此 `GET /admin/jobs/{id}` **查不到**这里返回的 job（没有落库）——
   那是契约里另一个端点的能力，本阶段没做，别把它当成「任务丢了」。
2. **写库失败要能说清是哪一环**：嵌入模型没配 → 400（去面板配）；Qdrant 连不上 → 502 + 人话提示
   （把 `QDRANT_BASE_URL` 与集合名打出来）。语料为空 → 建出空集合，并如实回 `total_posts=0`
   （而不是假装成功）。
3. **只增不删的权威仍然是向量库本身**：`recreate=True` 会先重建集合（清空），
   `removed_keys` 用来删掉语料里已经不存在的文档。日常增量走对账任务（⑥），不靠这条端点。
4. **文档标识是 `(kind, id)`**：文章 3 与笔记 3 是两个文档，单篇重建必须同时给出
   `contentKind`（默认 `post`），否则会一次命中两篇。
"""

from __future__ import annotations

import logging
import uuid
from typing import Any

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.core.config import (
    ENV_FILE,  # noqa: F401 - 触发 .env 加载（QdrantConfig.from_env 读 os.environ）
)
from app.providers import runtime
from app.providers.errors import ProviderError, ProviderQuotaExhaustedError
from app.rag import corpus as corpus_module
from app.rag.index_pipeline import IndexPipeline
from app.rag.index_reconcile import doc_key_of, reconcile
from app.rag.qdrant_store import QdrantConfig, QdrantVectorStore
from app.schemas.indexing import (
    IndexJob,
    IndexJobStatus,
    IndexRebuildRequest,
    IndexTaskKind,
)

logger = logging.getLogger(__name__)

router = APIRouter(tags=["index"])


def _job(kind: IndexTaskKind, status: IndexJobStatus, posts: int, failed: int = 0) -> IndexJob:  # noqa: E501
    return IndexJob(
        job_id=uuid.uuid4().hex,
        kind=kind,
        status=status,
        total_posts=posts,
        processed_posts=posts if status == IndexJobStatus.SUCCEEDED else 0,
        failed_posts=failed,
    )


@router.post("/admin/index/rebuild", summary="重建向量索引（真嵌真写）", response_model=None)
async def rebuild_index(request: IndexRebuildRequest) -> IndexJob | JSONResponse:
    """按语料重建索引；同步执行完才返回。

    请求体：`kind`（full_rebuild / post_rebuild）、`postId` + `contentKind`（单篇时定位文档）、
    `reason`（写日志）。
    """
    kind = request.kind
    if kind == IndexTaskKind.POST_REBUILD and not request.post_id:
        return JSONResponse(
            status_code=200,
            content={"code": 400, "message": "单篇重建需要给出 postId"},
        )

    posts = list(corpus_module.cached_posts())
    if kind == IndexTaskKind.POST_REBUILD:
        # 按**文档标识**取：只按数字 id 会同时命中文章 3 与笔记 3，然后一次重建两篇。
        # `content_kind` 可能是 null（Java 侧 DTO 默认值就是这么发过来的）→ 按 post
        target = (request.content_kind or "post", int(request.post_id or 0))
        posts = [post for post in posts if doc_key_of(post) == target]
        if not posts:
            return JSONResponse(
                status_code=200,
                content={
                    "code": 404,
                    "message": (
                        f"语料里没有 {target[0]}:{target[1]} 这篇"
                        "（未发布 / 已删除 / 不可见，或 contentKind 给错了）"
                    ),
                },
            )

    config = QdrantConfig.from_env()
    store = QdrantVectorStore(config)
    try:
        embedder = runtime.registry().embedding_model()
        report = await IndexPipeline(
            store=store, embedder=embedder, point_factory=store
        ).index(posts, recreate=kind == IndexTaskKind.FULL_REBUILD)
    except ProviderQuotaExhaustedError as error:
        # 额度用尽不是「稍后重试」：把它与瞬时错误分开说，否则会让人白折腾一天
        logger.warning("索引重建失败（额度用尽）：%s", error)
        return JSONResponse(status_code=200, content={"code": 429, "message": str(error)})
    except ProviderError as error:
        logger.warning("索引重建失败（模型侧）：%s", error)
        return JSONResponse(
            status_code=200,
            content={
                "code": 400,
                "message": (
                    f"嵌入模型不可用：{error}"
                    "（去 AI 实验室 → 模型配置里检查 embedding 角色）"
                ),
            },
        )
    except Exception as error:  # noqa: BLE001 - 连不上向量库/DNS/超时都归到这一类
        logger.warning("索引重建失败（向量库侧）：%s", error)
        return JSONResponse(
            status_code=200,
            content={
                "code": 502,
                "message": (
                    f"写向量库失败：{config.base_url}（集合 {config.collection}）：{error}"
                    "（本地直连测试机时核对 QDRANT_BASE_URL；用隧道时确认隧道还开着）"
                ),
            },
        )
    finally:
        await store.aclose()

    chunks = report.chunks
    logger.info(
        "索引重建完成：kind=%s 文档 %s 篇 / 子块 %s（reason=%s）",
        kind,
        len(posts),
        chunks,
        request.reason or "未给出",
    )
    return _job(kind, IndexJobStatus.SUCCEEDED, report.posts)


@router.post("/admin/index/reconcile", summary="对账式增量索引（只重嵌变了的、删掉没了的）")
async def reconcile_index() -> Any:  # Any：union 形式会让 FastAPI 推断响应模型并报错
    """把「语料」与「索引里已有什么」对一次账，只做必要的事。

    为什么增量要靠**对账**而不是靠事件：事件会丢（服务重启、网络抖动、并发写），
    而漏一次就永久不一致；对账每一轮都会自愈。纯逻辑在 `app/rag/index_reconcile.py`
    （按段落哈希比对，已有用例覆盖），这里只负责接线：

        语料 → 索引里每篇的段落哈希（只读 payload，不读向量）→ 差异计划
             → 重嵌「变了/新增」的、删掉「语料里已经没有的」

    ⚠️ 两件要说清的事：
    1. **调用账尚未落到 `ai_call_log`**：嵌入调用发生在 Python，而调用账由 Java 侧
       （身份/配额/场景都在那边）写。本刀只把计数写进日志；要让它出现在「调用记录」里，
       需要 ai-service 侧记一笔 `scene=index`。
    2. **删除是权威动作**：语料里没有的文章，索引里的段落会被删掉 —— 这正是
       「下架 / 转私有之后不再被引用」的保证。
    """
    config = QdrantConfig.from_env()
    store = QdrantVectorStore(config)
    try:
        chunks = corpus_module.cached_corpus()
        indexed = await store.hashes_by_docs()
        plan = reconcile(chunks, indexed)
        logger.info(
            "索引对账：语料 %s 子块 / 索引 %s 篇 → 不变 %s、变更 %s、待删 %s",
            len(chunks),
            len(indexed),
            len(plan.unchanged),
            len(plan.changed),
            len(plan.removed),
        )
        by_key = {doc_key_of(post): post for post in corpus_module.cached_posts()}
        changed_docs = [by_key[key] for key in plan.changed if key in by_key]
        embedder = runtime.registry().embedding_model()
        report = await IndexPipeline(store=store, embedder=embedder, point_factory=store).index(
            changed_docs, removed_keys=plan.removed
        )
    except ProviderQuotaExhaustedError as error:
        return JSONResponse(status_code=200, content={"code": 429, "message": str(error)})
    except Exception as error:  # noqa: BLE001 - 向量库/模型侧的失败都归到这一类
        logger.warning("索引对账失败：%s", error)
        return JSONResponse(
            status_code=200,
            content={
                "code": 502,
                "message": f"索引对账失败：{config.base_url}（集合 {config.collection}）：{error}",
            },
        )
    finally:
        await store.aclose()

    # **裸数据**返回（与 /eval/* 等内部端点同形）：成功时不要再包一层 {code,message,data}，
    # 否则 Java 侧拿到的是「壳里的壳」，面板与日志里都得再剥一次。
    return {
        "unchanged": len(plan.unchanged),
        "reembedded": report.posts,
        "removed": len(plan.removed),
        "chunksWritten": report.chunks,
        "deletedPosts": report.deleted_posts,
        "embedCalls": report.embed_calls,
    }