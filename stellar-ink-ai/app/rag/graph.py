"""GraphRAG 的图检索核心（E5-1）：**Local Search** 与 **Global Search**。

前提：知识图已经有了实体、共现关系、主张与主题（E4 那十一段），
这里做的是「拿一个问题去图上找东西」——两种粒度：

* **Local Search**（局部）：问题里提到某个实体时，从它出发沿共现关系走一跳，
  把这一小片里的**主张与原文**收集起来。它回答的是「关于 X 的文章里说了什么」。
* **Global Search**（全局）：问题更像在问「整体上都有哪些话题」时，
  按**主题**（共现连通分量）聚合，返回每个主题的关键词与代表主张。
  它回答的是「这个知识库都覆盖了什么」。

⚠️ 两条刻意的设计决定：

1. **图上走的是共现，不是因果**（E4-5 的口径）。所以这里的措辞也必须是
   「这些问题一起被谈论」，不能写成「X 导致了 Y」——检索结果的说服力来自原文，
   而不是来自「图」这个形式。
2. **它必须与普通 RAG 比过才有资格存在**（fast-track-plan 的约定）。
   所以这一层做成**独立的检索策略**、可被评测台按同一批问题跑：
   「图上走一跳」是不是真的比「向量检索」更能找到跨文章的依据，要用数字回答，
   而不是因为它叫 GraphRAG 就默认更好。
"""

from __future__ import annotations

import time
from collections import Counter
from dataclasses import dataclass, field
from typing import Any

#: 一跳扩展时只看权重大于等于它的边（弱关系一跳就能连到半个库，见 topics.py 的同一口径）
DEFAULT_MIN_WEIGHT = 1
#: 局部检索最多带出多少条主张：再多就不是「这一小片」了
DEFAULT_MAX_CLAIMS = 12
#: 全局检索最多回几个主题
DEFAULT_MAX_TOPICS = 5


@dataclass(frozen=True, slots=True)
class GraphHit:
    """图检索的一条结果：一条主张 + 它为什么被捞出来（**可核对**）。"""

    post_id: int
    chunk_index: int
    claim_text: str
    quote: str
    #: 这条主张是通过哪个实体命中的（读者/评测都要看得见「凭什么把它拿出来」）
    via: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "postId": self.post_id,
            "chunkIndex": self.chunk_index,
            "claimText": self.claim_text,
            "quote": self.quote,
            "via": list(self.via),
        }


@dataclass(frozen=True, slots=True)
class GraphContext:
    """图检索的结果：命中实体、走过的边、捞出的主张，以及**为什么是这样**。"""

    mode: str
    seeds: list[str] = field(default_factory=list)
    neighbors: list[str] = field(default_factory=list)
    hits: list[GraphHit] = field(default_factory=list)
    topics: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "mode": self.mode,
            "seeds": list(self.seeds),
            "neighbors": list(self.neighbors),
            "topics": list(self.topics),
            "hits": [hit.to_dict() for hit in self.hits],
            "notes": list(self.notes),
        }


class GraphIndex:
    """把知识图装进内存，供检索用。

    刻意做成一个**显式的小对象**而不是一组函数 + 全局字典：
    检索要能对着「某一版图」跑（评测里要比较不同构建结果的检索质量），
    而全局状态会让「这一次跑的是哪一版」变得说不清。
    """

    def __init__(
        self,
        claims: list[Any],
        mentions: list[Any],
        relations: list[Any],
        clusters: list[Any] | None = None,
    ) -> None:
        #: 规范化实体名 → 代表写法（没有 clusters 时退化成名字本身）
        self._name_of: dict[str, str] = {}
        for cluster in clusters or []:
            self._name_of[str(getattr(cluster, "normalized", ""))] = str(
                getattr(cluster, "name", "")
            )
        #: (post_id, chunk_index, claim_text) → 主张（去重用）
        self._claims: dict[tuple[int, int, str], Any] = {}
        for claim in claims:
            key = (
                int(getattr(claim, "post_id", 0)),
                int(getattr(claim, "chunk_index", 0)),
                str(getattr(claim, "text", "")),
            )
            self._claims.setdefault(key, claim)

        #: 实体 → 它出现在哪些主张上
        self._claim_index: dict[str, list[tuple[int, int, str]]] = {}
        for mention in mentions:
            normalized = str(getattr(mention, "normalized", ""))
            if not normalized:
                continue
            self._name_of.setdefault(normalized, str(getattr(mention, "name", normalized)))
            key = (
                int(getattr(mention, "post_id", 0)),
                int(getattr(mention, "chunk_index", 0)),
                str(getattr(mention, "claim_text", "")),
            )
            self._claim_index.setdefault(normalized, []).append(key)

        #: 无向邻接表（两端都记）
        self._neighbors: dict[str, list[tuple[str, int]]] = {}
        for relation in relations:
            source = str(getattr(relation, "source", ""))
            target = str(getattr(relation, "target", ""))
            weight = int(getattr(relation, "weight", 0))
            if not source or not target:
                continue
            self._neighbors.setdefault(source, []).append((target, weight))
            self._neighbors.setdefault(target, []).append((source, weight))

    @property
    def entity_count(self) -> int:
        return len(self._claim_index)

    def entities(self) -> list[str]:
        return sorted(self._claim_index)

    def local_search(
        self,
        query: str,
        *,
        min_weight: int = DEFAULT_MIN_WEIGHT,
        max_claims: int = DEFAULT_MAX_CLAIMS,
    ) -> GraphContext:
        """局部检索：问题里命中的实体 → 沿共现边一跳 → 收集这一片的主张。"""
        seeds = self.match_entities(query)
        if not seeds:
            # 没命中实体**不是错误**：问题可能是全新的说法。如实说「没在图上找到落点」，
            # 让调用方回退到向量检索，而不是返回一堆看似相关的边
            return GraphContext(
                mode="local",
                notes=[
                    "问题里没有命中任何已知实体 —— 图检索这一路没有落点，"
                    "应当回退到向量检索（而不是拿一堆弱相关的边充数）。"
                ],
            )

        neighbors: list[str] = []
        for seed in seeds:
            for other, weight in self._neighbors.get(seed, []):
                if weight >= min_weight and other not in seeds and other not in neighbors:
                    neighbors.append(other)

        via: dict[tuple[int, int, str], list[str]] = {}
        for entity in [*seeds, *neighbors]:
            for key in self._claim_index.get(entity, []):
                via.setdefault(key, []).append(entity)

        hits: list[GraphHit] = []
        for key in sorted(via):
            claim = self._claims.get(key)
            if claim is None:
                continue
            hits.append(
                GraphHit(
                    post_id=key[0],
                    chunk_index=key[1],
                    claim_text=str(getattr(claim, "text", key[2])),
                    quote=str(getattr(claim, "quote", "")),
                    # 命中路径按名字排序，保证同一份图两次检索结果一致
                    via=sorted({self._name_of.get(item, item) for item in via[key]}),
                )
            )
            if len(hits) >= max_claims:
                break

        notes: list[str] = []
        if len(via) > len(hits):
            notes.append(
                f"这一片共有 {len(via)} 条主张，按上限只带回 {len(hits)} 条 ——"
                "上限是为了让上下文里全是「这一小片」，不是把整库塞进去。"
            )
        return GraphContext(
            mode="local",
            seeds=[self._name_of.get(item, item) for item in seeds],
            neighbors=[self._name_of.get(item, item) for item in neighbors],
            hits=hits,
            notes=notes,
        )

    def global_search(
        self,
        query: str,
        topics: list[Any],
        *,
        max_topics: int = DEFAULT_MAX_TOPICS,
    ) -> GraphContext:
        """全局检索：按**主题**聚合，回答「这个知识库都覆盖了什么」。

        问题与主题的匹配是**关键词命中**（主题名就是它的关键词组合，见 topics.py）——
        不做向量相似度：这一层要的是「确定、可解释」，而它本来也只在
        「问整体」的场景下用，精度压力不如局部检索。
        """
        normalized = _normalize(query)
        scored: list[tuple[int, str, Any]] = []
        for topic in topics:
            keywords = list(getattr(topic, "entities", []) or [])
            display = [self._name_of.get(item, item) for item in keywords]
            hit = sum(
                1 for item in [*keywords, *display] if item and _normalize(item) in normalized
            )
            if hit:
                scored.append((hit, str(getattr(topic, "name", "")), topic))
        # 命中数降序、名字升序：顺序确定，否则同一份数据两次检索的顺序不同
        scored.sort(key=lambda item: (-item[0], item[1]))

        if not scored:
            return GraphContext(
                mode="global",
                notes=[
                    "问题与已知主题的关键词都不重合 —— 全局检索这一路的结论是"
                    "「知识库里还没有这个话题」，而不是「没有相关内容」。"
                ],
            )

        selected = scored[:max_topics]
        hits: list[GraphHit] = []
        for _, _, topic in selected:
            for post_id, chunk_index, claim_text in sorted(getattr(topic, "evidence", []) or []):
                hits.append(
                    GraphHit(
                        post_id=int(post_id),
                        chunk_index=int(chunk_index),
                        claim_text=str(claim_text),
                        quote="",
                        via=[str(getattr(topic, "name", ""))],
                    )
                )
        return GraphContext(
            mode="global",
            topics=[name for _, name, _ in selected],
            hits=hits,
            notes=[
                f"命中 {len(scored)} 个主题，带回前 {len(selected)} 个 ——"
                "全局检索给的是「覆盖了什么」，具体某一篇说了什么要用局部检索。"
            ]
            if len(scored) > len(selected)
            else [],
        )

    def match_entities(self, query: str) -> list[str]:
        """问题里提到了哪些已知实体（**按最长优先**，避免长名字被短名字吃掉）。"""
        normalized = _normalize(query)
        matched = [entity for entity in self._claim_index if entity and entity in normalized]
        # 长名字优先：有了「每天写五百字」就不需要再算「五百字」这个子串
        matched.sort(key=lambda item: (-len(item), item))
        kept: list[str] = []
        for entity in matched:
            if any(entity in longer and entity != longer for longer in kept):
                continue
            kept.append(entity)
        return sorted(kept)


def _normalize(text: str) -> str:
    """与实体归一化同一套口径（全角→半角、去空白、大小写折叠），避免两处各写一套。"""
    from app.rag.entities import normalize_entity

    return normalize_entity(text)


def graph_stats(context: GraphContext) -> Counter[str]:
    """检索结果的规模（给日志/评测用）。"""
    stats: Counter[str] = Counter()
    stats["hits"] = len(context.hits)
    stats["seeds"] = len(context.seeds)
    stats["neighbors"] = len(context.neighbors)
    stats["topics"] = len(context.topics)
    return stats


def graph_from_payload(payload: dict[str, Any]) -> GraphIndex:
    """从**一次抽取的返回体**装出图（就是 `/wiki/claims` 响应的形状）。

    为什么从返回体装而不是从库里读：Python 不碰库（M0 起的边界），而抽取结果本来就要
    经过 Java 落库。这条路径让「同一份结果」既能落库、也能直接喂给检索 ——
    评测里因此不需要再造一份「图的数据格式」：**格式只有一个**。
    """
    claims = [
        ClaimLike(
            text=str(item.get("text", "")),
            post_id=int(item.get("postId", 0)),
            chunk_index=int(item.get("chunkIndex", 0)),
            quote=str(item.get("quote", "")),
        )
        for item in payload.get("claims", []) or []
        if isinstance(item, dict)
    ]
    clusters: list[Any] = []
    mentions: list[Any] = []
    for entity in payload.get("entities", []) or []:
        if not isinstance(entity, dict):
            continue
        normalized = str(entity.get("normalized", ""))
        clusters.append(
            _ClusterLike(name=str(entity.get("name", normalized)), normalized=normalized)
        )
        for mention in entity.get("mentions", []) or []:
            if not isinstance(mention, dict):
                continue
            mentions.append(
                _MentionLike(
                    name=str(mention.get("name", normalized)),
                    normalized=normalized,
                    post_id=int(mention.get("postId", 0)),
                    chunk_index=int(mention.get("chunkIndex", 0)),
                    claim_text=str(mention.get("claimText", "")),
                )
            )
    relations = [
        _RelationLike(
            source=str(item.get("source", "")),
            target=str(item.get("target", "")),
            weight=int(item.get("weight", 0)),
        )
        for item in payload.get("relations", []) or []
        if isinstance(item, dict)
    ]
    return GraphIndex(claims, mentions, relations, clusters)


@dataclass(frozen=True, slots=True)
class ClaimLike:
    """检索只需要主张的这四个字段（不引入完整的抽取模型，避免两处定义打架）。"""

    text: str
    post_id: int
    chunk_index: int
    quote: str = ""


@dataclass(frozen=True, slots=True)
class _ClusterLike:
    name: str
    normalized: str


@dataclass(frozen=True, slots=True)
class _MentionLike:
    name: str
    normalized: str
    post_id: int
    chunk_index: int
    claim_text: str


@dataclass(frozen=True, slots=True)
class _RelationLike:
    source: str
    target: str
    weight: int


class GraphRetriever:
    """把图检索接成**检索器**（`app/rag/eval_runner.py` 的 `Retriever` 协议）。

    它一次模型都不调（图上走的是已有的实体与共现边），所以：

    * 可以在**没有额度**时被评测台选中、跑通、进对比表；
    * 但这也意味着它**不会**比别的策略更懂「没被抽取过的说法」——
      它的上限由**知识图的覆盖率**决定，而不是由检索算法决定。

    ⚠️ **离线（Fake 模型）跑出来的对比数字不能当收益证据**：那一刻其它策略用的是假嵌入。
    E5-2 的结论必须来自真实额度。
    """

    def __init__(
        self,
        index: GraphIndex,
        *,
        mode: str = "local",
        min_weight: int = DEFAULT_MIN_WEIGHT,
        max_claims: int = DEFAULT_MAX_CLAIMS,
    ) -> None:
        self._index = index
        self._mode = mode
        self._min_weight = min_weight
        self._max_claims = max_claims
        #: 上一次检索的提示语（为什么没命中）。
        #: ⚠️ 它**不在 `RetrievalOutcome` 里**：那个结构没有 reason 字段，
        #: 运行器是自己带一条 reason 的。所以这里挂在检索器上，供日志与用例读取 ——
        #: 编一个多余的字段塞进共用结构，会为了一个断言改掉所有调用方的形状。
        self.last_note = ""

    async def retrieve(self, question: str, *, top_k: int) -> Any:
        started = time.perf_counter()
        if self._mode == "global":
            # 全局检索要主题，而主题是**抽取阶段**算出来的；没传就如实说不可用，
            # 不能悄悄退回局部检索（那样「全局」这一行的数字解释不通）
            context = self._index.global_search(question, [])
        else:
            context = self._index.local_search(
                question, min_weight=self._min_weight, max_claims=self._max_claims
            )
        posts = _ordered_posts(context, top_k)
        self.last_note = "" if posts else ("；".join(context.notes) or "图上没有落点")
        return _outcome(
            posts=posts, context=context, latency_ms=(time.perf_counter() - started) * 1000
        )


def _ordered_posts(context: GraphContext, top_k: int) -> list[int]:
    """命中的文章按结果顺序去重（图检索没有分数，顺序本身就是证据强度）。"""
    ordered: list[int] = []
    for hit in context.hits:
        if hit.post_id not in ordered:
            ordered.append(hit.post_id)
    return ordered[:top_k]


def _outcome(*, posts: list[int], context: GraphContext, latency_ms: float) -> Any:
    """装成 `RetrievalOutcome`（延迟导入：graph 模块不该依赖评测运行器）。"""
    from app.rag.eval_runner import RetrievalOutcome

    return RetrievalOutcome(
        posts=posts,
        chunks=[],
        cited_chunks=[],
        # **没有命中就如实 refused**：不是「检索到 0 篇」，而是「图里没有落点」——
        # 两者在指标上都是 0，但在「该不该回退向量检索」上是两个结论
        refused=not posts,
        latency_ms=latency_ms,
    )
