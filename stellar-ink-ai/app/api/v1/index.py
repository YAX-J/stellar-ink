"""索引重建（M4 遗留缺口的补齐）：**真嵌真写**向量库。

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
   `removed_post_ids` 用来删掉语料里已经不存在的文档。日常增量走对账任务（⑥），不靠这条端点。
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.core.config import (
    ENV_FILE,  # noqa: F401 - 触发 .env 加载（QdrantConfig.from_env 读 os.environ）
)
from app.providers import runtime
from app.providers.errors import ProviderError, ProviderQuotaExhaustedError
from app.rag import corpus as corpus_module
from app.rag.index_pipeline import IndexPipeline
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


@router.post("/index/rebuild", summary="重建向量索引（真嵌真写）", response_model=None)
async def rebuild_index(request: IndexRebuildRequest) -> IndexJob | JSONResponse:
    """按语料重建索引；同步执行完才返回。

    请求体：`kind`（full_rebuild / post_rebuild）、`post_id`（单篇时必填）、`reason`（写日志）。
    """
    kind = request.kind
    if kind == IndexTaskKind.POST_REBUILD and not request.post_id:
        return JSONResponse(
            status_code=200,
            content={"code": 400, "message": "单篇重建需要给出 postId"},
        )

    posts = list(corpus_module.cached_posts())
    if kind == IndexTaskKind.POST_REBUILD:
        posts = [post for post in posts if post.post_id == request.post_id]
        if not posts:
            return JSONResponse(
                status_code=200,
                content={
                    "code": 404,
                    "message": f"语料里没有 id={request.post_id} 的文章（未发布/已删除/不可见）",
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
        "索引重建完成：kind=%s 文章 %s 篇 / 子块 %s（reason=%s）",
        kind,
        len(posts),
        chunks,
        request.reason or "未给出",
    )
    return _job(kind, IndexJobStatus.SUCCEEDED, report.posts)
