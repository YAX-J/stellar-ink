"""种子语料的**唯一缓存**：问答、Agent、评测、画像共用同一份内容包口径。

为什么要有它：以前每个端点各自 `build_corpus(load_seed_posts())` 再各自 `lru_cache` ——
同一份 SQL 被解析好几遍，`corpus_source` 的说法也开始各有各的写法（一旦不一致，
「面板上的数字」与「脚本里的数字」就再也没法对照）。语料是**只读**的，缓存一份就够。

两个刻意的口径：

1. **`EPOCH` 是语料版本号**，`reset_corpus()` 才递增。检索管道按它做缓存键的一部分：
   语料换了而管道还拿着旧下标，引用就会指向另一段文字 —— 这类错「看起来一切正常」，
   所以宁可让缓存键跟着语料一起换代。
2. **调用方不得修改返回的列表**。它们是共享的进程级对象，就地排序一次就会污染所有调用方。

真实数据源（Java 推送 / 向量库）落地时只改这一个模块，端点不必动。
"""

from __future__ import annotations

from functools import lru_cache

from app.rag.pipeline import IndexedChunk, build_corpus
from app.rag.seed_corpus import SeedPost, default_seed_sql, load_seed_posts

#: 语料版本号：`reset_corpus()` 递增。检索管道的缓存键含它（见 `api/v1/assembly.py`）
EPOCH = 0


@lru_cache(maxsize=1)
def cached_posts() -> tuple[SeedPost, ...]:
    """种子文章（画像是按文章统计的，要的是这一层而不是切好的块）。"""
    return tuple(load_seed_posts())


@lru_cache(maxsize=1)
def cached_corpus() -> list[IndexedChunk]:
    """检索用的子块（切块结果）。**共享对象，调用方不要就地修改**。"""
    return build_corpus(list(cached_posts()))


def corpus_source() -> str:
    """写进评测响应的「语料来源」：让人知道这些数字是在哪份数据上算出来的。"""
    return f"seed-sql:{default_seed_sql().name}"


def reset_corpus() -> None:
    """丢掉语料缓存并递增版本号（测试与「换了内容包想立刻生效」时用）。"""
    global EPOCH  # noqa: PLW0603 - 它就是「当前语料是哪一份」的唯一记号
    EPOCH += 1
    cached_posts.cache_clear()
    cached_corpus.cache_clear()
