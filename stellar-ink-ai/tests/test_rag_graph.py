"""图检索（E5-1）：Local / Global Search 的纯逻辑。

这一层要守的是「**图上走的是共现、不是因果**」与「**没命中不是错误**」两条：

* 共现：结果的措辞与字段都叫「一起被谈论」（`via` 说明凭什么捞出来），
  产出的是**原文可核对的主张**，而不是「图告诉我们 X 与 Y 有关」；
* 没命中：问题里没有已知实体时返回空结果 + 一条明确的提示
  （应当回退到向量检索），**不能**拿一堆弱相关的边充数 ——
  那正是「图检索看起来总能给点东西」这种假象的来源。

另外 GraphRAG 必须与普通 RAG **比过**才有资格存在（fast-track-plan 的约定），
所以这里也测「同一份图两次检索结果完全一致」：评测要能复现，才谈得上比较。
"""

from __future__ import annotations

from app.rag.entities import EntityCluster, EntityMention, EntityRelation, relation_edges
from app.rag.graph import GraphIndex, graph_stats
from app.rag.topics import build_topics
from app.rag.wiki import WikiClaim


def claim(post_id: int, chunk_index: int, text: str, quote: str = "原文片段") -> WikiClaim:
    return WikiClaim(
        text=text,
        post_id=post_id,
        chunk_index=chunk_index,
        post_version="v1",
        content_hash=f"hash{chunk_index}",
        quote=quote,
    )


def mention(name: str, text: str, *, post_id: int = 7, chunk_index: int = 0) -> EntityMention:
    return EntityMention(
        name=name,
        normalized=name.casefold(),
        kind="concept",
        post_id=post_id,
        chunk_index=chunk_index,
        claim_text=text,
    )


def cluster(name: str, mentions: list[EntityMention]) -> EntityCluster:
    return EntityCluster(name=name, normalized=name.casefold(), kind="concept", mentions=mentions)


def relation(source: str, target: str, weight: int) -> EntityRelation:
    return EntityRelation(source=source.casefold(), target=target.casefold(), weight=weight)


def index() -> GraphIndex:
    """两篇文章的小图：文章 7 讲「每天写五百字 / 十八万字」，文章 9 讲「深夜写作 / 手机」。"""
    claims = [
        claim(7, 0, "每天写五百字可以累积成十八万字", "每天写五百字，一年就是十八万字"),
        claim(7, 1, "写作的关键是把目标切到小得不可能失败", "把目标切到小得不可能失败"),
        claim(9, 0, "深夜写作要先清掉手机干扰", "先把手机放到另一个房间"),
    ]
    mentions = [
        mention("每天写五百字", "每天写五百字可以累积成十八万字"),
        mention("十八万字", "每天写五百字可以累积成十八万字"),
        mention("深夜写作", "深夜写作要先清掉手机干扰", post_id=9),
        mention("手机干扰", "深夜写作要先清掉手机干扰", post_id=9),
    ]
    clusters = [
        cluster("每天写五百字", [mentions[0]]),
        cluster("十八万字", [mentions[1]]),
        cluster("深夜写作", [mentions[2]]),
        cluster("手机干扰", [mentions[3]]),
    ]
    relations = [
        relation("每天写五百字", "十八万字", 3),
        relation("深夜写作", "手机干扰", 2),
    ]
    return GraphIndex(claims, mentions, relations, clusters)


def test_local_search_seeds_on_mentioned_entity() -> None:
    context = index().local_search("每天写五百字这件事怎么坚持")

    assert context.mode == "local"
    assert context.seeds == ["每天写五百字"]
    assert context.hits, "命中实体就该带回它所在的主张"
    assert "十八万字" in context.neighbors, "一跳邻居要带出来（它们被一起谈论）"


def test_local_search_explains_why_each_hit_was_picked() -> None:
    """`via` 是「凭什么捞出来」—— 没有它，图检索的产出无法核对。"""
    hit = index().local_search("十八万字是怎么来的").hits[0]

    assert hit.post_id == 7
    assert "十八万字" in hit.via
    assert hit.quote, "结果里要带原文片段，评测与展示都靠它"


def test_local_search_does_not_leak_across_communities() -> None:
    """「每天写五百字」与「深夜写作」之间没有边，一跳就不该跨过去。"""
    context = index().local_search("每天写五百字")

    assert "深夜写作" not in context.neighbors
    assert all(hit.post_id == 7 for hit in context.hits)


def test_longest_entity_wins_over_its_substring() -> None:
    """正文里有「每天写五百字」时，不该再把它里面的短词也当成另一个落点。"""
    graph = GraphIndex(
        [claim(7, 0, "每天写五百字可以累积成十八万字")],
        [mention("每天写五百字", "每天写五百字可以累积成十八万字")],
        [],
        [],
    )

    assert graph.match_entities("每天写五百字") == ["每天写五百字"]


def test_no_entity_match_is_not_an_error() -> None:
    """没命中 → 空结果 + 明确提示（回退向量检索），**不是**返回一堆弱相关的边。"""
    context = index().local_search("量子纠缠与咖啡因代谢的关系")

    assert context.hits == []
    assert context.seeds == []
    assert any("回退到向量检索" in note for note in context.notes)


def test_min_weight_filters_weak_edges() -> None:
    """弱边一跳就能连到半个库：提高阈值只保留「被反复一起谈论」的关系。"""
    graph = index()

    loose = graph.local_search("每天写五百字", min_weight=1)
    strict = graph.local_search("每天写五百字", min_weight=3)

    assert "十八万字" in loose.neighbors
    assert "十八万字" in strict.neighbors, "权重 3 的边在阈值 1 与 3 下都该保留"


def test_max_claims_is_reported_when_truncated() -> None:
    """截断要说出来：否则「只带回 1 条」看起来像「这一片只有 1 条」。"""
    # 这一片里得真有两条以上主张才谈得上截断（第一版用了只有一条的图，
    # 红的是测试数据而不是实现）
    graph = GraphIndex(
        [
            claim(7, 0, "每天写五百字可以累积成十八万字"),
            claim(7, 1, "每天写五百字的习惯比灵感可靠"),
        ],
        [
            mention("每天写五百字", "每天写五百字可以累积成十八万字"),
            mention("每天写五百字", "每天写五百字的习惯比灵感可靠", chunk_index=1),
        ],
        [],
        [],
    )

    context = graph.local_search("每天写五百字", max_claims=1)

    assert len(context.hits) == 1
    assert any("上限" in note for note in context.notes)


def test_local_search_is_deterministic() -> None:
    """评测要能复现，才谈得上「与普通 RAG 比较」。"""
    graph = index()

    first = graph.local_search("每天写五百字").to_dict()
    second = graph.local_search("每天写五百字").to_dict()

    assert first == second


def test_global_search_matches_topics_by_keyword() -> None:
    mentions = [
        mention("每天写五百字", "每天写五百字可以累积成十八万字"),
        mention("十八万字", "每天写五百字可以累积成十八万字"),
    ]
    clusters = [cluster("每天写五百字", [mentions[0]]), cluster("十八万字", [mentions[1]])]
    # ⚠️ 主题的 evidence 来自**关系的证据**（topics.build_topics 就是这么填的）：
    # 用一个「有空证据」的关系去建主题，主题页上就没有原文 —— 第一版正是这样，
    # 于是断言 hits 非空时红了（红的是测试数据）
    relations = relation_edges(mentions)
    topics, _ = build_topics(clusters, relations)
    graph = GraphIndex(
        [claim(7, 0, "每天写五百字可以累积成十八万字")], mentions, relations, clusters
    )

    context = graph.global_search("我想了解每天写五百字相关的话题", topics)

    assert context.mode == "global"
    assert context.topics, "关键词命中就该带回主题"
    assert context.hits, "主题页要带可核对的原文（来自关系的证据）"
    assert context.hits[0].via == context.topics[0:1], "结果要能追溯到主题"


def test_global_search_says_when_topic_is_unknown() -> None:
    """没有相关主题时如实说「知识库还没覆盖这个话题」，而不是编一个最接近的。"""
    topics, _ = build_topics([], [])
    context = index().global_search("聊聊分布式事务", topics)

    assert context.topics == []
    assert context.hits == []
    assert any("还没有这个话题" in note for note in context.notes)


def test_stats_shape_for_logging() -> None:
    stats = graph_stats(index().local_search("每天写五百字"))

    assert stats["hits"] >= 1
    assert stats["seeds"] == 1
