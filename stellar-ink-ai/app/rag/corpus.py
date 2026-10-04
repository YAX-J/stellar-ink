"""语料的**唯一缓存**：问答、Agent、评测、画像共用同一份内容口径。

来源优先级（见 `content_source`）：
1. **线上投影表** `ai_content_snapshot`（已发布文章 + 已发布且 PUBLIC 的笔记，由 ai-service 投影）；
2. 读不到线上（迁移没跑 / 连不上库 / 缺列）时回退**种子包** `deploy/sql/02_init-data.sql`，
   并且 `corpus_source()` 会如实说明是哪一种 —— 评测响应里带着它，
   否则「这批数字是在哪份数据上算的」永远说不清。

两个刻意的口径：

1. **`EPOCH` 是语料版本号**，`reset_corpus()` 才递增。检索管道按它做缓存键的一部分：
   语料换了而管道还拿着旧下标，引用就会指向另一段文字 —— 这类错「看起来一切正常」，
   所以宁可让缓存键跟着语料一起换代。
2. **调用方不得修改返回的列表**。它们是共享的进程级对象，就地排序一次就会污染所有调用方。
"""

from __future__ import annotations

import logging
from functools import lru_cache

from app.rag.content_source import (
    CorpusSourceUnavailable,
    SnapshotDoc,
    describe_source,
    load_snapshot_posts,
)
from app.rag.pipeline import IndexedChunk, PostLike, build_corpus
from app.rag.seed_corpus import SeedPost, default_seed_sql, load_seed_posts

logger = logging.getLogger(__name__)

#: 语料版本号：`reset_corpus()` 递增。检索管道的缓存键含它（见 `api/v1/assembly.py`）
EPOCH = 0

#: 这次语料到底从哪来（`corpus_source()` 返回它；与 `cached_posts()` 一起换代）
_SOURCE = ""


def _seed_posts() -> tuple[SeedPost, ...]:
    return tuple(load_seed_posts())


@lru_cache(maxsize=1)
def _online_posts() -> tuple[SnapshotDoc, ...] | None:
    """线上语料（投影表）。

    :return 文档元组；**表存在但没有内容时返回空元组**（那是「站内确实没有公开内容」，
        不能回退种子包 —— 回退会把早先删掉的文章重新拿来回答）；
        读不到（迁移没跑 / 连不上 / 缺列）时返回 None，调用方回退种子包。
    """
    try:
        docs = load_snapshot_posts()
    except CorpusSourceUnavailable as error:
        logger.warning("读不到线上语料，本次回退种子包：%s", error)
        return None
    if not docs:
        logger.warning(
            "ai_content_snapshot 里没有可用的已发布文章：语料为空。"
            "若这是刚部署/刚迁移，先跑 SQL 并让 ai-service 同步一次"
            "（POST /ai/admin/corpus/sync）；若站内确实没有公开文章，那这就是对的"
        )
    return tuple(docs)


@lru_cache(maxsize=1)
def cached_posts() -> tuple[PostLike, ...]:
    """语料文档（画像是按文章统计的，要的是这一层而不是切好的块）。"""
    global _SOURCE  # noqa: PLW0603 - 它与返回值是一体的，必须一起设
    online = _online_posts()
    if online is None:
        _SOURCE = f"seed-sql:{default_seed_sql().name}（线上语料不可用，已回退）"
        return _seed_posts()
    _SOURCE = describe_source()
    if not online:
        _SOURCE = f"{describe_source()}（0 篇：投影表里没有已发布文章）"
    return online


@lru_cache(maxsize=1)
def cached_corpus() -> list[IndexedChunk]:
    """检索用的子块（切块结果）。**共享对象，调用方不要就地修改**。"""
    return build_corpus(list(cached_posts()))


def corpus_source() -> str:
    """写进评测响应的「语料来源」：让人知道这些数字是在哪份数据上算出来的。"""
    if not _SOURCE:
        cached_posts()  # 触发一次装配，把来源定下来
    return _SOURCE


def reset_corpus() -> None:
    """丢掉语料缓存并递增版本号（测试与「换了内容包想立刻生效」时用）。"""
    global EPOCH  # noqa: PLW0603 - 它就是「当前语料是哪一份」的唯一记号
    global _SOURCE  # noqa: PLW0603 - 来源与语料一起换代，否则会报上一次的来源
    EPOCH += 1
    _SOURCE = ""
    _online_posts.cache_clear()
    cached_posts.cache_clear()
    cached_corpus.cache_clear()
