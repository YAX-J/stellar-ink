"""带证据的主张抽取（E4-1）：LLM Wiki 的第一道工序。

口径照 `docs/ai/README.md` §5.5 与 `implementation-roadmap.md` §14：

- 主张是**原子的**（一件事一句），并绑定 `post_id` + 段落序号 + **内容版本** + 原文片段；
- **引用必须被观察到**：模型给的 `quote` 必须真的出现在它引用的那个段落里
  （规范化空白后做子串匹配），否则**丢弃**。这条与 Agent 的引用核实是同一条纪律，
  也正是验收口径「Wiki 的事实性文本必须能回到证据」的落地方式 ——
  没有它，Wiki 就只是「模型的印象」。
- **丢弃要分类计数**并返回给调用方。静默丢弃会让「抽出来很少」看起来像模型不行，
  而真相往往是校验挡掉了一批编造的引用 —— 两者的处置完全不同。

这一版刻意不做的：实体消歧、关系、社区发现、页面生成、增量失效（都是后续切片）。
先把「一条能回到原文的主张」做扎实，后面每一层都建在它上面。
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
import time
from collections import Counter
from collections.abc import Coroutine
from dataclasses import dataclass, field
from typing import Any

from app.providers.base import ChatModel
from app.providers.models import ChatMessage, MessageRole
from app.rag.entities import (
    ENTITY_KINDS,
    EntityCluster,
    EntityMention,
    EntityRelation,
    collect_entities,
    merge_entities,
    relation_edges,
)
from app.rag.pipeline import IndexedChunk
from app.rag.topics import Topic, build_topics, topic_stats

logger = logging.getLogger(__name__)

#: 一条主张的正文长度范围。太短不是主张（「他很好」），太长就不再原子
MIN_CLAIM_CHARS = 8
MAX_CLAIM_CHARS = 200
#: 证据片段的下限：一个词的「引用」证明不了任何东西
MIN_QUOTE_CHARS = 4
#: 一次抽取最多问几篇文章（全量是离线批处理，不是一次请求该干的事）
DEFAULT_MAX_POSTS = 5
#: 每个段落最多接受几条主张（防止一段话被拆成二十条「主张」）
DEFAULT_MAX_CLAIMS_PER_CHUNK = 3

#: 丢弃原因（写进统计，前端与排障都按这些键读）
DROP_UNKNOWN_CHUNK = "unknownChunk"
DROP_QUOTE_NOT_FOUND = "quoteNotFound"
DROP_TEXT_TOO_SHORT = "textTooShort"
DROP_TEXT_TOO_LONG = "textTooLong"
DROP_DUPLICATE = "duplicate"

#: JSON 示例**单独放一个常量**：直接写在提示词里的话，`str.format` 会把
#: `{"claims": …}` 当成字段名去取值，报一个与真实原因毫不相干的 KeyError。
CLAIM_JSON_EXAMPLE = (
    '{"claims":[{"text":"原子主张","chunkIndex":0,'
    '"quote":"该段落里的原文片段","confidence":0.8}],'
    '"entities":[{"name":"实体名","kind":"concept"}]}'
)

PROMPT = """你在为一篇中文技术博客建立**带证据的知识条目**。

下面会给你一篇文章的若干段落，每段带一个段落序号（chunkIndex）。
请抽取**原子主张**（一句话只说一件事，且必须能在某个段落里找到原文依据），
并顺带列出主张里出现的**实体**。

只输出 JSON，不要解释：
{example}

要求：
- `quote` **必须逐字来自**你标注的那个段落（可以截取片段，但不要改写、不要拼接两处）；
- 每段最多 {max_per_chunk} 条，没有依据就不要写；
- `confidence` 是 0 到 1 之间的小数，表示你对「这段原文确实支持这条主张」的把握；
- `entities` 的 `name` **必须能在某条主张或它的原文片段里逐字找到**；`kind` 取值：{kinds}；
- 宁可少写：写得少只是内容薄，编一条就要靠人去核对。

文章：《{title}》

{chunks}"""


@dataclass(frozen=True, slots=True)
class WikiClaim:
    """一条带证据的主张。字段与跨语言契约一一对应（驼峰）。"""

    text: str
    post_id: int
    chunk_index: int
    #: 文章内容版本（切块时算的）：文章改了，主张就该失效重建
    post_version: str
    #: 段落内容哈希：用于「只失效受影响的那几条」
    content_hash: str
    quote: str
    heading_path: str = ""
    confidence: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        return {
            "text": self.text,
            "postId": self.post_id,
            "chunkIndex": self.chunk_index,
            "postVersion": self.post_version,
            "contentHash": self.content_hash,
            "quote": self.quote,
            "headingPath": self.heading_path,
            "confidence": round(self.confidence, 3),
        }


@dataclass(frozen=True, slots=True)
class ExtractionStats:
    """抽取账：提出多少、留下多少、按什么原因丢了多少（主张与实体各一份）。"""

    proposed: int
    kept: int
    dropped: dict[str, int] = field(default_factory=dict)
    posts: int = 0
    #: 实体：模型提出多少、通过证据校验多少、合并成几个实体簇
    entity_proposed: int = 0
    entity_kept: int = 0
    entities: int = 0
    #: 共现关系条数（见 entities.relation_edges）
    relations: int = 0
    #: 主题个数（见 topics.build_topics：连通分量 + 边权阈值）
    topics: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "proposed": self.proposed,
            "kept": self.kept,
            "dropped": dict(self.dropped),
            "posts": self.posts,
            "entityProposed": self.entity_proposed,
            "entityKept": self.entity_kept,
            "entities": self.entities,
            "relations": self.relations,
            "topics": self.topics,
        }


@dataclass(frozen=True, slots=True)
class ExtractionResult:
    claims: list[WikiClaim]
    stats: ExtractionStats
    #: 合并后的实体（按出现次数排序）；实体必须依附在留下来的主张上
    entities: list[EntityCluster] = field(default_factory=list)
    #: 实体之间的**共现**关系（每条边都带着它来自哪几句主张）
    relations: list[EntityRelation] = field(default_factory=list)
    #: 主题（共现图上的连通分量）—— 主题页的原料
    topics: list[Topic] = field(default_factory=list)
    #: 给人看的提示（例如「有 N 条因引用找不到被丢弃」）
    notes: list[str] = field(default_factory=list)
    usage_model: str = ""
    latency_ms: int = 0


def _run_sync(coro: Coroutine[Any, Any, ExtractionResult]) -> ExtractionResult:
    """在没有事件循环的场景里跑异步实现（测试与离线脚本用）。"""
    import asyncio

    return asyncio.run(coro)


def _assert_no_running_loop() -> None:
    """**先检查再创建协程**。

    顺序反了的话（先造协程、`asyncio.run` 再报错）那个协程永远不会被 await，
    Python 会在回收时丢一条「coroutine was never awaited」的告警 ——
    而这条告警会挂在**当时正在跑的那个无关用例**上，排查方向直接被带偏。
    """
    import asyncio

    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return
    raise RuntimeError(
        "同步入口不能在运行中的事件循环里调用（running event loop）："
        "在异步上下文里偷偷开一个循环会阻塞整个服务，请直接用 extract_claims_async。"
    )


def extract_claims(
    chunks: list[IndexedChunk],
    chat: ChatModel,
    *,
    max_posts: int = DEFAULT_MAX_POSTS,
    max_claims_per_chunk: int = DEFAULT_MAX_CLAIMS_PER_CHUNK,
) -> ExtractionResult:
    """同步入口（测试与离线脚本）。

    **只有一份实现**：校验逻辑全在 `extract_claims_async` 里，这里只是驱动它。
    写成两份（各带一遍校验）迟早会分叉，而分叉的表现是「脚本里能过的引用在端点里过不了」。
    """
    _assert_no_running_loop()
    return _run_sync(
        extract_claims_async(
            chunks, chat, max_posts=max_posts, max_claims_per_chunk=max_claims_per_chunk
        )
    )


async def extract_claims_async(
    chunks: list[IndexedChunk],
    chat: ChatModel,
    *,
    max_posts: int = DEFAULT_MAX_POSTS,
    max_claims_per_chunk: int = DEFAULT_MAX_CLAIMS_PER_CHUNK,
) -> ExtractionResult:
    """真正打模型的那条路（端点用它），也是**唯一一份**校验实现。

    为什么按**文章**而不是按段落问模型：主张的原子性要看整篇（同一件事可能在两段里各说一半），
    按段落问会得到一堆互不相干的碎片；代价是每篇一次调用，所以有 `max_posts` 上限。
    """
    if max_posts < 1:
        raise ValueError("max_posts 必须为正：0 篇就什么都抽不出来")
    if max_claims_per_chunk < 1:
        raise ValueError("max_claims_per_chunk 必须为正")

    started = time.perf_counter()
    grouped = _group_by_post(chunks)[:max_posts]
    dropped: Counter[str] = Counter()
    claims: list[WikiClaim] = []
    proposed = 0
    usage_model = ""
    seen: set[tuple[int, str]] = set()
    #: 本文通过校验的主张（实体要依附在它们上面，见 entities.py 的口径）
    kept_of_post: list[WikiClaim] = []
    mentions: list[EntityMention] = []
    entity_proposed = 0

    for post_id, post_chunks in grouped:
        response = await chat.chat(
            [ChatMessage(MessageRole.USER, _prompt(post_chunks, max_claims_per_chunk))]
        )
        usage_model = response.usage.model or usage_model
        parsed = _parse_claims(response.text)
        raw_entities = _parse_entities(response.text)
        entity_proposed += len(raw_entities)
        proposed += len(parsed)
        kept_of_post = []
        for item in parsed:
            claim, reason = _verify(item, post_id=post_id, post_chunks=post_chunks, seen=seen)
            if claim is None:
                dropped[reason] += 1
                continue
            seen.add((post_id, _normalize(claim.text)))
            claims.append(claim)
            kept_of_post.append(claim)

        # 实体在**本篇文章的主张都校验完之后**处理：它必须依附在留下来的主张上
        mentions.extend(
            collect_entities(raw_entities, post_id=post_id, claims=kept_of_post, dropped=dropped)
        )

    clusters = merge_entities(mentions)
    relations = relation_edges(mentions)
    topics, topic_notes = build_topics(clusters, relations)
    stats = ExtractionStats(
        proposed=proposed,
        kept=len(claims),
        dropped={reason: count for reason, count in dropped.items() if count},
        posts=len(grouped),
        entity_proposed=entity_proposed,
        entity_kept=len(mentions),
        entities=len(clusters),
        relations=len(relations),
        topics=len(topics),
    )
    logger.info(
        "主张抽取：文章 %d 篇，提出 %d 条，留下 %d 条，丢弃 %s；"
        "实体 提出 %d、留下 %d、合并成 %d；共现关系 %d；主题 %d（规模分布 %s）",
        stats.posts,
        stats.proposed,
        stats.kept,
        stats.dropped or "无",
        stats.entity_proposed,
        stats.entity_kept,
        stats.entities,
        stats.relations,
        stats.topics,
        dict(topic_stats(topics)) or "无",
    )
    return ExtractionResult(
        claims=claims,
        entities=clusters,
        relations=relations,
        topics=topics,
        stats=stats,
        notes=[*_notes(stats), *topic_notes],
        usage_model=usage_model,
        latency_ms=int((time.perf_counter() - started) * 1000),
    )


# --------------------------------------------------------------------- 内部


def _group_by_post(chunks: list[IndexedChunk]) -> list[tuple[int, list[IndexedChunk]]]:
    """按文章分组，**保持语料顺序**（同一篇文章的段落按 chunkIndex 排）。

    顺序很重要：提示词里的段落序号必须与校验时用的同一份列表一致，
    否则模型引用的 chunkIndex 会被解释成另一段 —— 那正好是「引用看着对、指向错」的形态。
    """
    grouped: dict[int, list[IndexedChunk]] = {}
    for chunk in chunks:
        grouped.setdefault(chunk.post_id, []).append(chunk)
    return [(post_id, sorted(items, key=_chunk_index)) for post_id, items in grouped.items()]


def _chunk_index(chunk: IndexedChunk) -> int:
    """段落序号：payload 是 `dict[str, object]`，这里安全转成 int（缺失或非法按 0）。"""
    raw = chunk.payload.get("chunkIndex")
    if isinstance(raw, (int, str)):
        try:
            return int(raw)
        except ValueError:
            return 0
    return 0


def _prompt(post_chunks: list[IndexedChunk], max_claims_per_chunk: int) -> str:
    title = post_chunks[0].title or f"文章 {post_chunks[0].post_id}"
    blocks = []
    for chunk in post_chunks:
        blocks.append(f"[chunkIndex={_chunk_index(chunk)}]\n{_chunk_text(chunk)}")
    return PROMPT.format(
        title=title,
        chunks="\n\n".join(blocks),
        max_per_chunk=max_claims_per_chunk,
        example=CLAIM_JSON_EXAMPLE,
        kinds="/".join(ENTITY_KINDS),
    )


def _chunk_text(chunk: IndexedChunk) -> str:
    """段落原文：优先用 payload 里的 `text`（切块时的那份原文），退回检索文本。

    为什么不用 `IndexedChunk.text`：那是**检索用文本**（标题 + 片段）。
    拿它当证据来源的话，模型引用标题也能通过校验 —— 而标题并不是「这一段的原文」。
    """
    payload_text = chunk.payload.get("text")
    if isinstance(payload_text, str) and payload_text.strip():
        return payload_text
    return chunk.text


def _parse_entities(raw: str) -> list[dict[str, Any]]:
    """解析模型输出里的实体列表（与主张同一次调用，不额外花钱）。"""
    payload = _parse_payload(raw)
    if payload is None:
        return []
    items = payload.get("entities")
    if not isinstance(items, list):
        return []
    return [item for item in items if isinstance(item, dict)]


def _parse_payload(raw: str) -> dict[str, Any] | None:
    """取出模型输出里的 JSON 对象。**解析失败返回 None 而不是抛错**：格式抖动不该炸掉整轮。"""
    text = (raw or "").strip()
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        payload = json.loads(text[start : end + 1])
    except ValueError:
        return None
    return payload if isinstance(payload, dict) else None


def _parse_claims(raw: str) -> list[dict[str, Any]]:
    """解析模型输出里的主张列表。**解析失败返回空列表而不是抛错**：一次格式抖动不该炸掉整轮抽取。"""
    payload = _parse_payload(raw)
    if payload is None:
        return []
    items = payload.get("claims")
    if not isinstance(items, list):
        return []
    return [item for item in items if isinstance(item, dict)]


def _verify(
    item: dict[str, Any],
    *,
    post_id: int,
    post_chunks: list[IndexedChunk],
    seen: set[tuple[int, str]],
) -> tuple[WikiClaim | None, str]:
    """逐条校验：返回 `(主张, "")` 或 `(None, 丢弃原因)`。"""
    text = str(item.get("text") or "").strip()
    if len(text) < MIN_CLAIM_CHARS:
        return None, DROP_TEXT_TOO_SHORT
    if len(text) > MAX_CLAIM_CHARS:
        return None, DROP_TEXT_TOO_LONG
    if (post_id, _normalize(text)) in seen:
        return None, DROP_DUPLICATE

    index = item.get("chunkIndex")
    if not isinstance(index, int):
        return None, DROP_UNKNOWN_CHUNK
    chunk = next(
        (candidate for candidate in post_chunks if _chunk_index(candidate) == index),
        None,
    )
    if chunk is None:
        return None, DROP_UNKNOWN_CHUNK

    quote = str(item.get("quote") or "").strip()
    if len(quote) < MIN_QUOTE_CHARS or _normalize(quote) not in _normalize(_chunk_text(chunk)):
        # **这就是「回到证据」的实现**：引用找不到原文依据 → 这条主张不成立
        return None, DROP_QUOTE_NOT_FOUND

    return (
        WikiClaim(
            text=text,
            post_id=post_id,
            chunk_index=index,
            post_version=str(chunk.payload.get("version") or _digest(_chunk_text(chunk))),
            content_hash=str(chunk.payload.get("contentHash") or _digest(_chunk_text(chunk))),
            quote=quote,
            heading_path=str(chunk.payload.get("headingPath") or ""),
            confidence=_confidence(item.get("confidence")),
        ),
        "",
    )


def _confidence(raw: Any) -> float:
    """置信度：数字就夹到 [0,1]；缺失或不是数字按 0.5（**不丢主张**，它只是没法自评）。"""
    if isinstance(raw, bool) or not isinstance(raw, (int, float)):
        return 0.5
    return min(1.0, max(0.0, float(raw)))


def _normalize(text: str) -> str:
    """规范化空白后再比对：模型常把换行/多空格写得不一致，那不是「引用不实」。"""
    return re.sub(r"\s+", "", text)


def _digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def _notes(stats: ExtractionStats) -> list[str]:
    notes: list[str] = []
    if stats.proposed and stats.dropped.get(DROP_QUOTE_NOT_FOUND):
        notes.append(
            f"{stats.dropped[DROP_QUOTE_NOT_FOUND]} 条主张因**引用找不到原文依据**被丢弃 —— "
            "这是校验在起作用，不是抽取失败。"
        )
    if stats.proposed and stats.kept == 0:
        notes.append("这一轮一条都没留下：先看 dropped 的分类，再判断是模型问题还是校验太严。")
    return notes
