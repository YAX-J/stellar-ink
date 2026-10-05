"""业务端点的公共装配层：模型、语料、检索管道，以及「装不起来」的统一翻译。

为什么要有这一层：问答 / Agent / 评测要的是同一件事 —— 按当前面板配置拿模型、
拿同一份语料、按「配置指纹 + 检索开关」复用同一条检索管道。各写一遍的后果不是
代码重复，而是**它们会慢慢分叉**：面板换了模型，一个端点换了、另一个还在用旧的。

管道缓存不是优化而是必需：`RetrievalPipeline.prepare()` 会把**整个语料**嵌入一遍，
每次请求重建就等于每问一句把整库嵌入一次 —— 那不是慢一点，是费用问题。
缓存键 = `(语料版本, 检索开关, 配置指纹)`，三者任一变化都会换一条新管道。

装配失败一律抛错，端点转成 400（见 `assembly_error`）：**没有「没配就用 Fake」这条兜底**。
"""

from __future__ import annotations

import logging
import os
from collections.abc import Sequence
from threading import Lock

from fastapi.responses import JSONResponse

from app.providers import runtime
from app.providers.config_source import ProviderConfigError, describe_sources
from app.providers.errors import ProviderError
from app.providers.models import ProviderConfig
from app.rag import corpus as corpus_module
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline
from app.rag.qdrant_store import QdrantConfig, QdrantVectorStore
from app.schemas.common import AiErrorCode

logger = logging.getLogger(__name__)

#: 部署期开关：dense 走向量库（true）还是内存通路（false，默认）。
#: 读一次、进管道缓存键 —— 一个进程只持有一个客户端（见 pipeline_for 的说明）。
_DENSE_STORE_FLAG = (os.environ.get("AI_DENSE_STORE_ENABLED") or "").strip().lower()
DENSE_STORE_ENABLED = _DENSE_STORE_FLAG in {"1", "true", "yes"}

#: 进程内唯一的向量库句柄（惰性创建；开关关掉时永远是 None）
_dense_store: QdrantVectorStore | None = None


def _shared_dense_store() -> QdrantVectorStore:
    """惰性建一个向量库句柄并复用：管道是缓存的，每次新建会漏连接。"""
    global _dense_store  # noqa: PLW0603 - 进程级单例，就是它的用途
    if _dense_store is None:
        _dense_store = QdrantVectorStore(QdrantConfig.from_env())
    return _dense_store

#: 已预热的检索管道：键见模块 docstring
_pipelines: dict[tuple[object, ...], RetrievalPipeline] = {}
_lock = Lock()


class CorpusError(RuntimeError):
    """语料不可用（内容包读不到 / 切出来是空的）。

    单独立一个类型而不是复用 `ValueError`：端点上「模型没按格式回答」也是 `ValueError`，
    而那种要报 502（上游问题）。混用一个类型，两种完全不同的故障就会被同一条 except 吞掉。
    """


def reset_assembly() -> None:
    """丢掉全部装配缓存：模型实例、语料、检索管道。

    三处一起清是刻意的 —— 分开清最容易漏一处，而漏掉的表现是
    「改了配置/语料却没生效」，排查方向会被带偏到「是不是没保存」。
    """
    runtime.invalidate()
    corpus_module.reset_corpus()
    with _lock:
        _pipelines.clear()


def use_provider_configs(configs: Sequence[ProviderConfig]) -> None:
    """把模型来源固定到给定配置（测试与离线脚本用），并清空装配缓存。"""
    runtime.use_provider_configs(configs)
    reset_assembly()


def pipeline_for(config: RetrievalConfig) -> RetrievalPipeline:
    """按当前配置取检索管道（同配置复用同一实例，因此整库嵌入只发生一次）。

    需要的角色由开关决定：纯 Sparse 的配置**不该**因为没配嵌入模型就报错，
    否则「只跑 BM25 的评测」会被一个它根本用不到的模型卡住。

    **dense 走内存还是走向量库**由部署期开关 `AI_DENSE_STORE_ENABLED` 决定：
    默认走内存（整库嵌入一次 + 本地余弦）—— 语料几十篇时它更快、且不依赖外部组件；
    打开后走向量库（查询只嵌问题，索引由重建/对账任务维护）。

    ⚠️ 三条刻意的口径：
    1. **开关是部署期的**：它在模块导入时读一次，并进管道缓存键。运行中改环境变量不生效 ——
       这不是缺陷，而是为了「一个进程只持有一个向量库客户端」（管道是缓存的，
       缓存住管道就等于缓存住客户端；能来回切的开关会漏连接）。
    2. **索引未验收前不要打开**：⑦ 的验收门槛是「黄金集 30 题不劣化」，
       没跑过就打开等于把一个没量过的通路接到用户面前。打开时请在评测台上标注状态。
    3. **payload 过滤（只允许公开/已发布）还没做**，所以打开前必须确认索引里
       确实只有公开内容 —— 今天的保证来自上游投影（`ai_content_snapshot` 只含
       content-service 返回的已发布文章与公开笔记）。⚠️ 千万**不要**先加一个过滤：
       若 payload 里没有对应字段，Qdrant 的过滤会**静默命中零条**，
       问答会变成「没有依据」，而日志里一切正常。
    """
    registry = runtime.registry()
    embedder = registry.embedding_model() if config.enable_dense else None
    reranker = registry.rerank_model() if config.enable_rerank else None

    chunks = corpus_module.cached_corpus()
    if not chunks:
        # 空语料不会报错，只会让每次检索都「无依据地拒答」—— 看起来像模型不行
        raise CorpusError("语料为空：检索没有可查的内容（种子内容包没读到？）")

    with _lock:
        key = (corpus_module.EPOCH, config, runtime.fingerprint(), DENSE_STORE_ENABLED)
        cached = _pipelines.get(key)
        if cached is None:
            # 语料换代（TTL 到期 / 手动 reset）之后，旧 `EPOCH` 的管道**再也用不到**，就地删掉：
            # 每条管道持有整份语料与 BM25 索引，留着就是「每过一轮 TTL 多一份语料的内存」。
            # 这里不需要关连接 —— dense 客户端是进程级单例（`_shared_dense_store`），不在管道里。
            for stale_key in [k for k in _pipelines if k[0] != corpus_module.EPOCH]:
                _pipelines.pop(stale_key, None)
            cached = RetrievalPipeline(
                corpus=chunks,
                config=config,
                embedder=embedder,
                reranker=reranker,
                dense_store=_shared_dense_store() if DENSE_STORE_ENABLED else None,
            )
            _pipelines[key] = cached
        return cached


def roles_for(config: RetrievalConfig, *, chat: bool = True) -> list[str]:
    """一段检索配置需要哪些模型角色（顺带算上对话角色）。

    端点在装配**之前**用它一次性预检：缺 chat 又缺 embedding 时，
    逐个取实例的写法只会报「先被取到的那个」，用户改完一个再被告知还缺另一个 ——
    两次往返本可以是一次。
    """
    roles = ["chat"] if chat else []
    if config.enable_dense:
        roles.append("embedding")
    if config.enable_rerank:
        roles.append("rerank")
    return roles


def assembly_error(error: Exception) -> JSONResponse:
    """把「装配不起来」翻译成可读的 400。

    两种情况都是**配置/环境问题**，不该伪装成 500 让人去翻栈：
    - 模型角色没配 / 能力不符（`ProviderError`）—— 必须说清「去面板配哪个角色」，
      因为最常见的误判是「服务坏了」，而实际只是没填；
    - 配置读不出来（`ProviderConfigError`）—— 例如 `MYSQL_*` 没给齐。

    「角色尚未配置」会附上 `describe_sources()`：空配置有两个完全不同的原因
    （真没配 / 读不到），混成一句话会把排查方向带偏。
    """
    message = str(error)
    if "尚未配置" in message and "来源" not in message:
        message = f"{message}；{describe_sources()}"
    return JSONResponse(
        status_code=400,
        content={"code": AiErrorCode.BAD_REQUEST.value, "message": message},
    )


#: 端点要捕获的「装配失败」：配置读不出来、角色没配 / 能力不符、语料不可用
ASSEMBLY_ERRORS: tuple[type[Exception], ...] = (ProviderError, ProviderConfigError, CorpusError)
