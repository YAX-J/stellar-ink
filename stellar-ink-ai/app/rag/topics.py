"""主题社区发现与页面草稿（E4-8）：LLM Wiki 的第三道工序。

**做什么**：把实体共现图切成「主题」——同一主题里的实体被反复一起谈论，
主题页就是「把这些实体、它们的主张与出处聚成一页人读的东西」。

**怎么做（以及为什么只做这么多）**：v1 用**连通分量 + 边权阈值**，刻意不用 Louvain：

- 它是**确定性的**：同一份数据两次构建得到完全相同的主题与顺序（Louvain 要固定随机种子，
  而它内部还有遍历顺序依赖），而我们后面要靠 diff 看「主题页变了没有」；
- 它是**可解释的**：任何一个主题都能回答「为什么这几个实体在一起」——
  「它们之间存在一条权重大于阈值的共现边链」；
- 它的失败方式是**看得见的**：文章一多，弱关系会把半个知识库连成一片，
  于是得到一个巨大的主题。这时正确的动作是**提高 `min_weight`**（只保留被反复一起谈论的关系），
  而不是忍着一个大杂烩 —— 所以阈值是一个显式参数，而且在结果里如实报出来。

⚠️ **明确的局限**：连成一片时它**不会**自动切分（那需要模块度优化）。已记录为后续切片，
  不做「假装切开了」的处理：把一个大组硬拆成几块，看起来更像知识图谱，实际上没有任何依据。
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from typing import Any

#: 一个主题最多展示几个关键词（页面上「这页讲什么」的那一行）
MAX_KEYWORDS = 3


@dataclass(frozen=True, slots=True)
class Topic:
    """一个主题：一组被反复一起谈论的实体 + 它们的证据。"""

    #: 主题名（由权重最高的几个实体名拼成）——**不是**模型起的标题，
    #: 所以它读起来会像「关键词组合」；真正的拟人化标题属于后续的页面生成
    name: str
    #: 该主题涉及实体的规范化名字（顺序确定：按提及数、再按名字）
    entities: list[str]
    #: 主题内的共现边总权重（「这几个东西被一起谈论的程度」）
    weight: int
    #: 出自哪几篇文章
    post_ids: list[int] = field(default_factory=list)
    #: `(postId, chunkIndex, claimText)`：主题页上那段可核对的原文
    evidence: list[tuple[int, int, str]] = field(default_factory=list)

    @property
    def size(self) -> int:
        return len(self.entities)

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "keywords": self.entities[:MAX_KEYWORDS],
            "entities": list(self.entities),
            "size": self.size,
            "weight": self.weight,
            "postIds": list(self.post_ids),
            "evidence": [
                {"postId": post_id, "chunkIndex": chunk_index, "claimText": claim_text}
                for post_id, chunk_index, claim_text in self.evidence
            ],
        }


def build_topics(
    clusters: list[Any],
    relations: list[Any],
    *,
    min_weight: int = 1,
    max_topics: int = 8,
) -> tuple[list[Topic], list[str]]:
    """把实体簇与共现关系聚成主题。

    :param clusters: `entities.EntityCluster`（要有 `normalized` / `mentions`）
    :param relations: `entities.EntityRelation`（`source` / `target` / `weight` / `evidence`）
    :param min_weight: 只保留权重大于等于它的边 —— 这是**唯一**的收窄手段（见模块说明）
    :param max_topics: 最多回几个主题（按权重降序截断）；被截掉的**不静默丢**，由调用方记进 notes
    :returns `(主题列表, 提示语列表)`

    主题内实体按「提及数降序、名字升序」排；主题之间按「权重降序、名字升序」排 ——
    顺序**确定**，否则同一份数据两次构建的页面 diff 里全是噪声。
    """
    accepted = [rel for rel in relations if int(getattr(rel, "weight", 0)) >= min_weight]
    accepted = [rel for rel in accepted if rel.source != rel.target]

    mention_count = {
        str(cluster.normalized): len(getattr(cluster, "mentions", []) or []) for cluster in clusters
    }
    name_of = {str(cluster.normalized): str(cluster.name) for cluster in clusters}

    # 连通分量：先并查集，再把结果收成组（比逐步 BFS 少一半代码，且天然与遍历顺序无关）
    parent: dict[str, str] = {}

    def find(node: str) -> str:
        parent.setdefault(node, node)
        while parent[node] != node:
            parent[node] = parent[parent[node]]
            node = parent[node]
        return node

    def union(left: str, right: str) -> None:
        root_left, root_right = find(left), find(right)
        if root_left != root_right:
            # 固定「字典序小的当根」：否则分量代表会随输入顺序变，主题顺序跟着抖
            low, high = sorted((root_left, root_right))
            parent[high] = low

    edges: dict[str, list[tuple[str, int]]] = {}
    for relation in accepted:
        source, target = str(relation.source), str(relation.target)
        union(source, target)
        edges.setdefault(source, []).append((target, int(relation.weight)))
        edges.setdefault(target, []).append((source, int(relation.weight)))

    groups: dict[str, list[str]] = {}
    for node in edges:
        groups.setdefault(find(node), []).append(node)

    # 有实体但一条边都没有的：**单独报出来**，不硬塞进某个主题
    isolated = sorted(
        str(cluster.normalized) for cluster in clusters if str(cluster.normalized) not in edges
    )

    topics: list[Topic] = []
    for members in groups.values():
        member_set = set(members)
        weight = sum(
            int(relation.weight)
            for relation in accepted
            if str(relation.source) in member_set and str(relation.target) in member_set
        )
        # 主题的实体顺序：提及多的在前，其次按名字（确定性）
        ordered = sorted(members, key=lambda item: (-mention_count.get(item, 0), item))
        evidence: list[tuple[int, int, str]] = []
        post_ids: set[int] = set()
        for relation in accepted:
            if str(relation.source) not in member_set or str(relation.target) not in member_set:
                continue
            for post_id, chunk_index, claim_text in getattr(relation, "evidence", []) or []:
                evidence.append((int(post_id), int(chunk_index), str(claim_text)))
                post_ids.add(int(post_id))
        for normalized in ordered:
            for mention in getattr(_cluster_of(clusters, normalized), "mentions", []) or []:
                post_ids.add(int(mention.post_id))
        topics.append(
            Topic(
                name=_topic_name(ordered, name_of),
                entities=ordered,
                weight=weight,
                post_ids=sorted(post_ids),
                evidence=sorted(set(evidence)),
            )
        )

    topics.sort(key=lambda topic: (-topic.weight, topic.name))
    notes: list[str] = []
    if len(topics) > max_topics:
        notes.append(
            f"主题共 {len(topics)} 个，只回了权重最高的 {max_topics} 个 —— "
            "被截掉的没有丢，下次构建按同样的规则还会算出来。"
        )
        topics = topics[:max_topics]
    if isolated:
        # 孤立实体不是「坏数据」：它只是还没被和别的东西一起谈论过。
        # 但也不能放进主题页（那等于替它编一个归属），所以如实报数。
        notes.append(
            f"{len(isolated)} 个实体还没有和任何实体共现过（例如「{isolated[0]}」），"
            "因此没有归入任何主题。"
        )
    return topics, notes


def _cluster_of(clusters: list[Any], normalized: str) -> Any:
    for cluster in clusters:
        if str(cluster.normalized) == normalized:
            return cluster
    return None


def _topic_name(ordered: list[str], name_of: dict[str, str]) -> str:
    """主题名 = 前几个实体的**代表写法**拼起来。

    刻意不叫「标题」：它不是模型拟的，所以别指望它读起来像一句话。
    反过来它有一个好处 —— **它总是诚实的**：名字就是这页里的东西。
    """
    parts = [name_of.get(item, item) for item in ordered[:MAX_KEYWORDS]]
    return " · ".join(parts) if parts else "（空主题）"


def topic_stats(topics: list[Topic]) -> Counter[str]:
    """主题规模的分布（给日志/构建报告用）：`{'size1': 3, 'size2': 1}` 这种。"""
    stats: Counter[str] = Counter()
    for topic in topics:
        stats[f"size{topic.size}"] += 1
    return stats
