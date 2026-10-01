"""实体抽取与别名合并（E4-4）：LLM Wiki 的第二道工序。

口径照 `docs/ai/implementation-roadmap.md` §14 的第 1-2 步：抽取实体与别名并做消歧。
两条刻意的决定：

1. **实体必须站在留下来的主张上**。实体不是从整篇文章里自由捞出来的名词 ——
   它必须出现在**本文某条通过校验的主张**、或那条主张的原文片段里。
   理由与 E4-1 校验引用是同一条：脱离证据的抽取无法核验，
   而「实体挂在哪条主张上」正是后面关系与页面能回到原文的那条线。
2. **别名合并只做确定性归一化**（全角/半角、大小写、空白、首尾标点），
   不做语义合并。「星笺」与「STELLAR INK」是同一个东西，但任何规则都可能把
   不该合的合到一起；roadmap §14 第 2 步明确要求**低置信项进 ADMIN 审核**，
   那套审核流还没做，所以这里宁可不合 —— 少合一组的代价是页面上多一个条目，
   错合一组的代价是一个说不清的知识条目。**已记录为后续切片。**
"""

from __future__ import annotations

import re
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from typing import Any

#: 允许的实体类型；模型给了别的就归到 `other`（不丢实体本身，它仍然有用）
ENTITY_KINDS = ("person", "concept", "tool", "org", "place", "other")

MIN_ENTITY_CHARS = 2
MAX_ENTITY_CHARS = 40

#: 丢弃原因（写进统计，与主张的丢弃原因并列）
DROP_ENTITY_NOT_IN_TEXT = "entityNotInText"
DROP_ENTITY_TOO_SHORT = "entityTooShort"
DROP_ENTITY_TOO_LONG = "entityTooLong"

#: 归一化时去掉的首尾字符：引号、括号与常见标点
_TRIM_CHARS = "「」『』《》〈〉“”‘’\"'()（）[]【】<>《》,，.。;；:：!！?？·-—_ \t\n"


@dataclass(frozen=True, slots=True)
class EntityMention:
    """一次具体出现：哪个实体、在哪篇文章的哪一段、挂在哪条主张上。"""

    name: str
    normalized: str
    kind: str
    post_id: int
    chunk_index: int
    claim_text: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "postId": self.post_id,
            "chunkIndex": self.chunk_index,
            "claimText": self.claim_text,
        }


@dataclass(frozen=True, slots=True)
class EntityCluster:
    """合并后的实体：一个规范化名字 + 它所有的出现。"""

    name: str
    normalized: str
    kind: str
    mentions: list[EntityMention] = field(default_factory=list)

    @property
    def count(self) -> int:
        return len(self.mentions)

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "normalized": self.normalized,
            "kind": self.kind,
            "count": self.count,
            # 去重后按出现顺序：页面要按它列出「出自哪几篇」
            "postIds": sorted({mention.post_id for mention in self.mentions}),
            "mentions": [mention.to_dict() for mention in self.mentions],
        }


def normalize_entity(name: str) -> str:
    """确定性的归一化：全角→半角、去空白、大小写折叠、去首尾标点。

    **不做**同义词/翻译合并（见模块 docstring）。
    """
    text = unicodedata.normalize("NFKC", str(name or ""))
    text = re.sub(r"\s+", "", text)
    return text.strip(_TRIM_CHARS).casefold()


def entity_kind(raw: Any) -> str:
    """类型归一化：白名单之外一律 `other`（写错类型不该让整条实体消失）。"""
    value = str(raw or "").strip().casefold()
    return value if value in ENTITY_KINDS else "other"


def collect_entities(
    raw_items: list[dict[str, Any]],
    *,
    post_id: int,
    claims: list[Any],
    dropped: Counter[str],
) -> list[EntityMention]:
    """校验一批原始实体（来自同一次模型调用），返回站得住脚的那些。

    `claims` 是**本文通过校验的主张**（已含 quote）。实体必须出现在其中某条主张
    或它的原文片段里 —— 否则丢弃并计数。
    """
    if not claims:
        # 一条主张都没留下时，实体没有任何可依附的证据：全部丢弃（而不是无主地留下）
        # ⚠️ 只在**真的有**实体时才计数：`counter[k] += 0` 会凭空插一个 0 值键，
        # 而统计里出现「丢弃 0 条」的原因会让人以为发生过这件事
        if raw_items:
            dropped[DROP_ENTITY_NOT_IN_TEXT] += len(raw_items)
        return []

    kept: list[EntityMention] = []
    for item in raw_items:
        name = str(item.get("name") or "").strip()
        normalized = normalize_entity(name)
        if len(normalized) < MIN_ENTITY_CHARS:
            dropped[DROP_ENTITY_TOO_SHORT] += 1
            continue
        if len(normalized) > MAX_ENTITY_CHARS:
            dropped[DROP_ENTITY_TOO_LONG] += 1
            continue
        evidences = _find_evidences(normalized, claims)
        if not evidences:
            # **这就是「实体也要有证据」的实现**：不在任何一条留下来的主张里 → 丢弃
            dropped[DROP_ENTITY_NOT_IN_TEXT] += 1
            continue
        # **每条匹配到的主张各记一次提及**（而不是只记第一条）：
        # 只记第一条会把「它还出现在哪几句」丢掉，而共现权重正是按「同处一句」算的 ——
        # 丢了这个信息，边权会系统性偏小（实测：两句话里的共现被算成 1）
        for claim_text, chunk_index in evidences:
            kept.append(
                EntityMention(
                    name=name,
                    normalized=normalized,
                    kind=entity_kind(item.get("kind")),
                    post_id=post_id,
                    chunk_index=chunk_index,
                    claim_text=claim_text,
                )
            )
    return kept


def merge_entities(mentions: list[EntityMention]) -> list[EntityCluster]:
    """按规范化名字合并成实体簇。

    排序：出现次数多的在前，其次按名字 —— 顺序**确定**，否则同一份数据两次构建
    会得到不同顺序的页面，diff 里全是噪声。
    """
    buckets: dict[str, list[EntityMention]] = {}
    for mention in mentions:
        buckets.setdefault(mention.normalized, []).append(mention)

    clusters: list[EntityCluster] = []
    for normalized, items in buckets.items():
        # 代表写法取出现最多的那种写法（并列时取字典序最小的，保证确定性）
        spellings = Counter(mention.name for mention in items)
        representative = sorted(spellings.items(), key=lambda pair: (-pair[1], pair[0]))[0][0]
        kinds = Counter(mention.kind for mention in items)
        kind = sorted(kinds.items(), key=lambda pair: (-pair[1], pair[0]))[0][0]
        clusters.append(
            EntityCluster(
                name=representative,
                normalized=normalized,
                kind=kind,
                mentions=sorted(items, key=lambda m: (m.post_id, m.chunk_index)),
            )
        )
    return sorted(clusters, key=lambda cluster: (-cluster.count, cluster.normalized))


def relation_edges(mentions: list[EntityMention]) -> list[EntityRelation]:
    """从实体的出现位置推出**共现关系**：同一句主张里同时出现的两个实体连一条边。

    为什么第一版用共现而不是「让模型抽关系」：
    - 它是**可核对**的 —— 每条边都带着「哪几篇文章的哪几句话同时提到了它们」，
      点开就是原文；模型抽的关系同样需要证据，而证据最终还是回到「它们是否同处一句」；
    - 它**不额外花钱**（不需要再一次模型调用），也不会引入一批无法验证的语义标签；
    - 边权是「共同出现的主张条数」，语义清楚：它衡量的是「被一起谈论的程度」。

    ⚠️ 它**不是**语义关系（因果、属于、依赖）：那些需要模型抽取 + 人工审核。
    这里如实叫「共现」，别在界面上把它说成「知识图谱中的因果关系」。

    无向边只有一种表示：两端按规范化名字排序，`source < target` —— 否则 (A,B) 与 (B,A)
    会各存一行，权重看起来只有实际的一半。
    """
    # 按「哪条主张」聚合：(post_id, chunk_index, claim_text) 唯一确定一条主张
    by_claim: dict[tuple[int, int, str], set[str]] = {}
    for mention in mentions:
        key = (mention.post_id, mention.chunk_index, mention.claim_text)
        by_claim.setdefault(key, set()).add(mention.normalized)

    weights: Counter[tuple[str, str]] = Counter()
    evidence: dict[tuple[str, str], list[tuple[int, int, str]]] = {}
    for (post_id, chunk_index, claim_text), entities in by_claim.items():
        pair_names = sorted(entities)
        for index, source in enumerate(pair_names):
            for target in pair_names[index + 1 :]:
                edge = (source, target)
                weights[edge] += 1
                evidence.setdefault(edge, []).append((post_id, chunk_index, claim_text))

    relations = [
        EntityRelation(
            source=source,
            target=target,
            weight=weight,
            evidence=sorted(evidence[(source, target)]),
        )
        for (source, target), weight in weights.items()
    ]
    return sorted(relations, key=lambda rel: (-rel.weight, rel.source, rel.target))


@dataclass(frozen=True, slots=True)
class EntityRelation:
    """一条共现关系（无向：`source < target`，两端都是规范化名字）。"""

    source: str
    target: str
    weight: int
    #: `(post_id, chunk_index, claim_text)`：这条边是从哪几条主张里看出来的
    evidence: list[tuple[int, int, str]] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "source": self.source,
            "target": self.target,
            "weight": self.weight,
            "evidence": [
                {"postId": post_id, "chunkIndex": chunk_index, "claimText": claim_text}
                for post_id, chunk_index, claim_text in self.evidence
            ],
        }


def _find_evidences(normalized: str, claims: list[Any]) -> list[tuple[str, int]]:
    """找出这条实体出现在**哪些**留下来的主张里（可能不止一条）。

    返回全部而不是第一条：共现关系是按「同处一句」计算的，只留第一条会让边权系统性偏小。
    """
    found: list[tuple[str, int]] = []
    for claim in claims:
        haystacks = (
            normalize_entity(getattr(claim, "text", "")),
            normalize_entity(getattr(claim, "quote", "")),
        )
        if any(normalized in haystack for haystack in haystacks):
            found.append((str(getattr(claim, "text", "")), int(getattr(claim, "chunk_index", 0))))
    return found
