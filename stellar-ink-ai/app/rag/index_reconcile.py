"""增量索引的判定（M4）：哪些文档要重建、哪些要删除、哪些可以不动。

roadmap M4 的原话是「先证明索引正确，再引入 Outbox 增量同步；不要在这一阶段直接上消息队列」。
所以这里**不是**事件总线，而是一次**对账**：

    当前语料（每篇的段落内容哈希）  ×  索引里已有的（每篇的段落内容哈希）
        →  不变 / 要重建 / 要删除

为什么不建「文章保存时发事件」的那套：那要求 content-service 与 ai-service 之间有同步调用
（本项目目前刻意没有），而且事件丢了就永久不一致 —— 而**对账**每次都能自己发现不一致。
代价是每次要读一遍索引的哈希（不读向量），换来的是「漂了也能自己收敛」。

三条口径：

1. **拿不到哈希就当「变了」**：索引里缺这篇、或老的 point 没有 `contentHash` 字段（早期数据），
   都按「要重建」处理。反过来假设「没变」的后果是**改动永远不进索引**，
   而它不会报错 —— 检索到旧内容看起来只是「答案有点过时」。
   重建的代价只是钱，静默不一致的代价是「系统看起来正常」。
2. **只看内容哈希，不看时间戳**：改了错别字又改回来，时间戳变了但内容没变，
   不该为它花钱重嵌。
3. **「不变」的价值要报出来**：全量重建与增量的差别就是这里省下的嵌入次数，
   不报出来就没人知道增量有没有生效。
4. **一切按文档标识 `(kind, id)` 走**：文章 3 与笔记 3 是两个文档。按裸数字 id 归并，
   「重建文章 3」会把笔记 3 的哈希当成自己的，于是漏掉真正该重建的那一篇 ——
   而现象只是「答案有点旧」。
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from typing import Any

#: 文档标识：内容种类（`post` / `note`）+ 该种类下的 id
DocKey = tuple[str, int]

#: 段落哈希在 payload 里的键名（与 `index_pipeline` 写入时用的一致）
CONTENT_HASH_KEY = "contentHash"


@dataclass(slots=True)
class ReconcilePlan:
    """对账结果：三份清单 + 说明。清单里装的是**文档标识** `(kind, id)`，不是裸数字 id。"""

    #: 内容没变，可以跳过（**省下的就是这个**）
    unchanged: list[DocKey] = field(default_factory=list)
    #: 新增或内容变了，要重建
    changed: list[DocKey] = field(default_factory=list)
    #: 索引里有、语料里没了，要删除
    removed: list[DocKey] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    #: 因为「拿不到哈希」而被判成 changed 的文档（单独数出来，好排查）
    unverifiable: list[DocKey] = field(default_factory=list)

    @property
    def rebuild_count(self) -> int:
        return len(self.changed)

    def to_dict(self) -> dict[str, Any]:
        """响应用形态：标识写成 `post:3` / `note:11` 这样的文本。

        不直接给二元数组（`["post", 3]`）：这份响应用来给人看「哪几篇要重建」，
        而嵌套数组在日志与界面上都难读。
        """
        return {
            "unchanged": [key_text(key) for key in self.unchanged],
            "changed": [key_text(key) for key in self.changed],
            "removed": [key_text(key) for key in self.removed],
            "unverifiable": [key_text(key) for key in self.unverifiable],
            "rebuildCount": self.rebuild_count,
            "notes": self.notes,
        }


def key_text(key: DocKey) -> str:
    """文档标识的文本形式（`post:3`）。"""
    return f"{key[0]}:{key[1]}"


def doc_key_of(chunk: Any) -> DocKey:
    """从一个子块/文档取文档标识；缺 `kind` 按 `post`（笔记接入之前的数据全是文章）。"""
    kind = getattr(chunk, "kind", None)
    normalized = kind if isinstance(kind, str) and kind in {"post", "note"} else "post"
    return (normalized, int(chunk.post_id))


def hashes_by_doc(chunks: Iterable[Any]) -> dict[DocKey, set[str]]:
    """把当前语料的子块按文档聚成「内容哈希集合」。

    只取哈希，不取向量 —— 对账要读的是「有没有变」，不是「像不像」。
    """
    grouped: dict[DocKey, set[str]] = {}
    for chunk in chunks:
        content_hash = _hash_of(chunk)
        if content_hash is None:
            continue
        grouped.setdefault(doc_key_of(chunk), set()).add(content_hash)
    return grouped


def reconcile(
    current_chunks: Iterable[Any],
    indexed_hashes: Mapping[DocKey, Iterable[str] | None],
) -> ReconcilePlan:
    """算增量索引计划。

    :param current_chunks: 当前语料的子块（`IndexedChunk`，payload 里带 `contentHash`）
    :param indexed_hashes: 索引里已有的：文档标识 → 该文档的段落哈希**集合**；
        **值为 None 表示「这篇读不到哈希」**（老数据或读取失败），按要重建处理。
        收 `Iterable` 而不是 `Sequence`：Qdrant 那边自然给出的是 `set`。
    """
    current = hashes_by_doc(current_chunks)
    plan = ReconcilePlan()

    for key in sorted(current):
        wanted = current[key]
        existing = indexed_hashes.get(key)
        if existing is None:
            # 索引里没有，或读不到哈希 → 重建（见模块 docstring 第 1 条）
            plan.changed.append(key)
            if key in indexed_hashes:
                plan.unverifiable.append(key)
            continue
        if set(existing) == wanted:
            plan.unchanged.append(key)
        else:
            plan.changed.append(key)

    plan.removed = sorted(set(indexed_hashes) - set(current))

    if plan.unchanged:
        plan.notes.append(
            f"{len(plan.unchanged)} 篇内容没变，跳过重建 —— 增量的意义就是省下这些嵌入。"
        )
    if plan.unverifiable:
        plan.notes.append(
            f"{len(plan.unverifiable)} 篇在索引里读不到段落哈希（早期数据或读取失败），"
            "已按「要重建」处理：假设没变会让改动**永远进不了索引**，而且不会报错。"
        )
    if plan.removed:
        plan.notes.append(
            f"{len(plan.removed)} 篇已从语料里消失（删除 / 下架 / 笔记转私有），"
            "索引里还留着它们的片段，要删。"
        )
    if not plan.changed and not plan.removed:
        plan.notes.append("索引与语料一致，本次不需要重建（这是增量索理想的稳态）。")
    return plan


def _hash_of(chunk: Any) -> str | None:
    """取一个子块的内容哈希（payload 里没有就回 None）。"""
    payload = getattr(chunk, "payload", None)
    if isinstance(payload, Mapping):
        value = payload.get(CONTENT_HASH_KEY)
        if isinstance(value, str) and value:
            return value
    return None
