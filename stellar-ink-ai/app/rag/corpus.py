"""语料的唯一缓存与公开视图。

线上只读 ai_content_snapshot；空库保持空，读取失败明确报错。
只有显式 AI_CORPUS_SOURCE=seed 的离线开发使用种子，test/prod 禁止 seed。

TTL 控制投影新鲜度；每次使用语料还要向内容服务实时复核公开版本。
撤回或正文改变会淘汰公开视图和旧检索管道，不等待同步任务与 TTL。
调用方不得修改共享列表；EPOCH 随投影换代或公开视图变化递增。
"""

from __future__ import annotations

import logging
import os
from collections.abc import Sequence
from functools import lru_cache
from threading import RLock
from time import monotonic

from app.rag.content_source import (
    CorpusSourceUnavailable,
    SnapshotDoc,
    describe_source,
    load_snapshot_docs,
)
from app.rag.pipeline import IndexedChunk, PostLike, build_corpus
from app.rag.public_corpus import visible_docs
from app.rag.seed_corpus import SeedPost, default_seed_sql, load_seed_posts

logger = logging.getLogger(__name__)

#: 语料默认有效期（秒）。取值理由见模块 docstring 第 2 条：它同时管新鲜度与隐私窗口。
DEFAULT_CORPUS_TTL_SECONDS = 60.0

#: 「只吃文章」的那些面（LLM Wiki / 写作画像 / 评测）用的内容种类。
#: 它们把文档 id 写进自己的表（`ai_wiki_claim.post_id`、`ai_memory_evidence.post_id`、
#: 黄金集的 `expectedPostIds`），而**文章 3 与笔记 3 是两个文档** —— 混进来就会互指。
#: 问答与 Agent **不用**这个常量：用户问的可能是笔记里的技术细节。
ARTICLE_KINDS: tuple[str, ...] = ("post",)

#: 语料版本号：换代（`reset_corpus()` 或 TTL 到期）时递增；检索管道的缓存键含它
EPOCH = 0
_STATE_LOCK = RLock()

#: 这次语料到底从哪来（`corpus_source()` 返回它；与语料一起换代）
_SOURCE = ""

#: 语料装入时刻（**单调时钟**，不是墙上时间：改系统时间不该让缓存永远不过期）。
#: `None` 表示还没装过 —— 此时没有必要判断过期。
_LOADED_AT: float | None = None

# 只有这份已复核的视图能交给调用方，底层 TTL 缓存不得直接进入模型上下文。
_VISIBLE_POSTS: tuple[PostLike, ...] = ()
_VISIBLE_INITIALIZED = False


def source_mode() -> str:
    """线上默认严格读取；离线测试必须显式选择 seed，生产禁止种子模式。"""
    mode = os.environ.get("AI_CORPUS_SOURCE", "online").strip().lower()
    if mode not in {"online", "seed"}:
        raise CorpusSourceUnavailable("AI_CORPUS_SOURCE 只接受 online / seed")
    if mode == "seed" and os.environ.get("AI_APP_ENV", "dev").lower() in {"prod", "test"}:
        raise CorpusSourceUnavailable("测试与生产环境禁止使用种子语料，请配置线上语料")
    return mode


def ttl_seconds() -> float:
    """语料有效期（秒）：`<=0` 表示永不过期。

    每次调用都读环境变量（而不是导入时读一次）：这个值要能被测试与本地联调改，
    而它只在每次请求装配语料时读一次，代价可以忽略。
    非法值（写成 `abc`）**不抛错**，退回默认值并留一条 warn ——
    一个拼错的可调项不该让整站问答起不来。
    """
    raw = (os.environ.get("AI_CORPUS_TTL_SECONDS") or "").strip()
    if not raw:
        return DEFAULT_CORPUS_TTL_SECONDS
    try:
        return float(raw)
    except ValueError:
        logger.warning(
            "AI_CORPUS_TTL_SECONDS 不是数字（%r），按默认 %.0fs 处理",
            raw,
            DEFAULT_CORPUS_TTL_SECONDS,
        )
        return DEFAULT_CORPUS_TTL_SECONDS


def _expire_if_stale() -> None:
    """TTL 到期就换代：投影表可能已经变了（新发布 / 下架 / 笔记转私有）。

    换代（而不只是重读）是必需的：检索管道的缓存键含 `EPOCH`，
    只清语料缓存会让管道继续拿着**旧的块下标**，引用就会指向另一段文字。
    """
    if _LOADED_AT is None:
        return
    ttl = ttl_seconds()
    if ttl <= 0:
        return
    age = monotonic() - _LOADED_AT
    if age < ttl:
        return
    logger.info(
        "语料缓存已过期（装入 %.1fs，TTL %.0fs）→ 重新读取：%s",
        age,
        ttl,
        _SOURCE or "语料",
    )
    reset_corpus()


def _seed_posts() -> tuple[SeedPost, ...]:
    return tuple(load_seed_posts())


@lru_cache(maxsize=1)
def _online_posts() -> tuple[SnapshotDoc, ...] | None:
    """线上语料（投影表）。

    :return 文档元组；**表存在但没有内容时返回空元组**（那是「站内确实没有公开内容」），
        离线模式返回 None；线上读取失败直接抛错，禁止种子回退。
    """
    if source_mode() == "seed":
        return None
    return tuple(load_snapshot_docs())


def _kind_of(doc: object) -> str:
    """文档的内容种类（`post` / `note`）。缺属性按 `post`：种子包与老数据都只有文章。"""
    kind = getattr(doc, "kind", None)
    return kind if isinstance(kind, str) and kind else "post"


def cached_posts(*, kinds: Sequence[str] | None = None) -> tuple[PostLike, ...]:
    """语料文档（画像是按文章统计的，要的是这一层而不是切好的块）。

    `kinds` 可按内容种类收窄（`None` = 全部：文章 + 笔记）。收窄是**内存里的过滤**，
    不会多读一次库 —— 全站只有一份全量语料，多个视图共用它。

    ⚠️ **Wiki 构建、写作画像、评测**这些「按 postId 定位」的既有面必须显式传
    `kinds=("post",)`：它们把 id 写进自己的表（`ai_wiki_claim.post_id` 等），
    而文章 3 与笔记 3 是两个不同的文档 —— 混进去就会互指。

    每次调用都会先判断 TTL（见模块 docstring 第 2 条），所以**它可能换代**——
    调用方不要把它当成「一个请求内恒定不变」的东西。
    """
    _refresh_visibility()
    with _STATE_LOCK:
        if kinds is None:
            return _VISIBLE_POSTS
        return _filtered_posts(tuple(sorted(set(kinds))))


@lru_cache(maxsize=4)
def _filtered_posts(kinds: tuple[str, ...]) -> tuple[PostLike, ...]:
    return tuple(doc for doc in _VISIBLE_POSTS if _kind_of(doc) in kinds)


@lru_cache(maxsize=1)
def _load_posts() -> tuple[PostLike, ...]:
    """真正读一次语料并记住装入时刻（缓存命中时不会走到这里，计时因此只跟着换代走）。"""
    global _SOURCE  # noqa: PLW0603 - 它与返回值是一体的，必须一起设
    global _LOADED_AT  # noqa: PLW0603 - 同上：装入时刻就是这份语料的属性
    online = _online_posts()
    docs: tuple[PostLike, ...]
    if online is None:
        _SOURCE = f"seed-sql:{default_seed_sql().name}（显式离线模式）"
        docs = _seed_posts()
    else:
        _SOURCE = describe_source()
        if not online:
            _SOURCE = f"{describe_source()}（0 篇：投影表里没有已发布文章）"
        docs = online
    _LOADED_AT = monotonic()
    return docs


def cached_corpus(*, kinds: Sequence[str] | None = None) -> list[IndexedChunk]:
    """检索用的子块（切块结果）。**共享对象，调用方不要就地修改**。

    `kinds` 的语义与 `cached_posts()` 相同（`None` = 文章 + 笔记）。
    问答与 Agent 要的是**全部**（用户问的可能是笔记里的技术细节）；
    Wiki / 画像 / 评测要的是 `("post",)`（见 `cached_posts()` 的说明）。

    与 `cached_posts()` 一样会先判断 TTL：切块结果与语料必须一起换代，
    否则「块下标」与「文档」会对不上（引用指到别的段落）。
    """
    _refresh_visibility()
    with _STATE_LOCK:
        if kinds is None:
            return _load_corpus()
        return _filtered_corpus(tuple(sorted(set(kinds))))


@lru_cache(maxsize=4)
def _filtered_corpus(kinds: tuple[str, ...]) -> list[IndexedChunk]:
    return build_corpus(list(_filtered_posts(kinds)))


@lru_cache(maxsize=1)
def _load_corpus() -> list[IndexedChunk]:
    return build_corpus(list(_VISIBLE_POSTS))


def _refresh_visibility() -> None:
    """每次使用都复核；撤回导致视图换代，旧检索管道随后按 EPOCH 淘汰。"""
    global EPOCH, _VISIBLE_POSTS, _VISIBLE_INITIALIZED  # noqa: PLW0603
    for _ in range(3):
        with _STATE_LOCK:
            _expire_if_stale()
            docs = _load_posts()
            epoch = EPOCH
        current = docs if source_mode() == "seed" else visible_docs(docs)
        with _STATE_LOCK:
            if epoch != EPOCH:
                continue
            if not _VISIBLE_INITIALIZED or current != _VISIBLE_POSTS:
                _VISIBLE_POSTS = current
                if _VISIBLE_INITIALIZED:
                    EPOCH += 1
                _VISIBLE_INITIALIZED = True
                _load_corpus.cache_clear()
                _filtered_posts.cache_clear()
                _filtered_corpus.cache_clear()
            return
    raise CorpusSourceUnavailable("语料正在更新，请重试")


def corpus_source() -> str:
    """写进评测响应的「语料来源」：让人知道这些数字是在哪份数据上算出来的。"""
    if not _SOURCE:
        cached_posts()  # 触发一次装配，把来源定下来
    return _SOURCE


def reset_corpus() -> None:
    """丢掉语料缓存并递增版本号（TTL 到期、测试与「换了内容包想立刻生效」时用）。"""
    global EPOCH  # noqa: PLW0603 - 它就是「当前语料是哪一份」的唯一记号
    global _SOURCE  # noqa: PLW0603 - 来源与语料一起换代，否则会报上一次的来源
    global _LOADED_AT  # noqa: PLW0603 - 换掉了就没有装入时刻可言
    global _VISIBLE_POSTS  # noqa: PLW0603 - 不允许复用上一代公开视图
    global _VISIBLE_INITIALIZED  # noqa: PLW0603
    with _STATE_LOCK:
        EPOCH += 1
        _SOURCE = ""
        _LOADED_AT = None
        _VISIBLE_POSTS = ()
        _VISIBLE_INITIALIZED = False
        _online_posts.cache_clear()
        _load_posts.cache_clear()
        _load_corpus.cache_clear()
        _filtered_posts.cache_clear()
        _filtered_corpus.cache_clear()
