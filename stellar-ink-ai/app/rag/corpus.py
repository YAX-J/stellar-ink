"""语料的**唯一缓存**：问答、Agent、评测、画像共用同一份内容口径。

来源优先级（见 `content_source`）：
1. **线上投影表** `ai_content_snapshot`（已发布文章 + 已发布且 PUBLIC 的笔记，由 ai-service 投影）；
2. 读不到线上（迁移没跑 / 连不上库 / 缺列）时回退**种子包** `deploy/sql/02_init-data.sql`，
   并且 `corpus_source()` 会如实说明是哪一种 —— 评测响应里带着它，
   否则「这批数字是在哪份数据上算的」永远说不清。

三个刻意的口径：

1. **`EPOCH` 是语料版本号**，换代（`reset_corpus()` 或 TTL 到期）才递增，检索管道按它做
   缓存键的一部分：语料换了而管道还拿着旧下标，引用就会指向另一段文字 ——
   这类错「看起来一切正常」，所以宁可让缓存键跟着语料一起换代。
2. **语料有有效期（TTL，`AI_CORPUS_TTL_SECONDS`，默认 60 秒）**：ai-service 每 5 分钟刷一遍投影表，
   但它**不会通知本进程**。没有 TTL 时语料只在首次请求时装一次、之后永不重读，后果是双向的：
   新发布的内容问不到；而**下架 / 已删 / 笔记转私有**的内容会继续被回答 ——
   最后一条是隐私问题：`CorpusSyncServiceImpl` 删掉投影行，本意就是让私有内容不再被引用，
   而本进程缓存里那份正文还在 —— 这不是新鲜度问题。
   `AI_CORPUS_TTL_SECONDS<=0` 表示永不过期，供离线脚本与单测使用。
3. **调用方不得修改返回的列表**。它们是共享的进程级对象，就地排序一次就会污染所有调用方。
"""

from __future__ import annotations

import logging
import os
from collections.abc import Sequence
from functools import lru_cache
from time import monotonic

from app.rag.content_source import (
    CorpusSourceUnavailable,
    SnapshotDoc,
    describe_source,
    load_snapshot_docs,
)
from app.rag.pipeline import IndexedChunk, PostLike, build_corpus
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

#: 这次语料到底从哪来（`corpus_source()` 返回它；与语料一起换代）
_SOURCE = ""

#: 语料装入时刻（**单调时钟**，不是墙上时间：改系统时间不该让缓存永远不过期）。
#: `None` 表示还没装过 —— 此时没有必要判断过期。
_LOADED_AT: float | None = None


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
        读不到（迁移没跑 / 连不上 / 缺列）时返回 None，调用方回退种子包。
    """
    try:
        docs = load_snapshot_docs()
    except CorpusSourceUnavailable as error:
        logger.warning("读不到线上语料，本次回退种子包：%s", error)
        return None
    if not docs:
        # ⚠️ 空快照 → **回退种子包**（2026-10-05 修正）：
        # 曾经把「表存在但没有行」当成「站内确实没有公开内容」而返回空语料。结果是
        # 「SQL 跑完、第一次同步还没成功」的那段窗口里整个知识库为空：每次提问都变成
        # 「没有依据」，画像类接口直接失去全部样本（实测：4 条 API 用例因此变红，
        # 正好把这个窗口暴露了出来 —— 它在真实部署里同样存在）。
        # 取舍：宁可让种子包顶上（种子内容本来就是本站自己的公开内容），也不让应用
        # 「什么都不知道」。隐私口径不受影响：私有/草稿的排除发生在上游投影，与本回退无关。
        logger.warning(
            "ai_content_snapshot 是空的（第一次同步还没成功跑过？）→ 本次回退种子包；"
            "若站内确实没有公开文章，这条会一直出现，可以忽略"
        )
        return None
    return tuple(docs)


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
    _expire_if_stale()
    if kinds is None:
        return _load_posts()
    return _filtered_posts(tuple(sorted(set(kinds))))


@lru_cache(maxsize=4)
def _filtered_posts(kinds: tuple[str, ...]) -> tuple[PostLike, ...]:
    return tuple(doc for doc in _load_posts() if _kind_of(doc) in kinds)


@lru_cache(maxsize=1)
def _load_posts() -> tuple[PostLike, ...]:
    """真正读一次语料并记住装入时刻（缓存命中时不会走到这里，计时因此只跟着换代走）。"""
    global _SOURCE  # noqa: PLW0603 - 它与返回值是一体的，必须一起设
    global _LOADED_AT  # noqa: PLW0603 - 同上：装入时刻就是这份语料的属性
    online = _online_posts()
    docs: tuple[PostLike, ...]
    if online is None:
        _SOURCE = f"seed-sql:{default_seed_sql().name}（线上语料不可用，已回退）"
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
    _expire_if_stale()
    if kinds is None:
        return _load_corpus()
    return _filtered_corpus(tuple(sorted(set(kinds))))


@lru_cache(maxsize=4)
def _filtered_corpus(kinds: tuple[str, ...]) -> list[IndexedChunk]:
    return build_corpus(list(_filtered_posts(kinds)))


@lru_cache(maxsize=1)
def _load_corpus() -> list[IndexedChunk]:
    return build_corpus(list(_load_posts()))


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
    EPOCH += 1
    _SOURCE = ""
    _LOADED_AT = None
    _online_posts.cache_clear()
    _load_posts.cache_clear()
    _load_corpus.cache_clear()
    _filtered_posts.cache_clear()
    _filtered_corpus.cache_clear()
