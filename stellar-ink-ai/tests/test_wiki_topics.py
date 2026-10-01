"""主题社区发现（E4-8）：**连通分量 + 边权阈值**。

这一层要守的是「主题页上的东西为什么在一起」这个问题的可回答性：
任何一个主题都能答出「它们之间有一条权重大于阈值的共现边链」。
所以用例测的不是「算法多聪明」，而是：

- 分开的两组实体**不会**被并成一个主题；
- 一条边都没有的实体**单独报出来**，不硬塞进某个主题（那等于替它编一个归属）；
- 树上的顺序**确定**（同一份数据两次构建得到同一个页面，否则 diff 里全是噪声）；
- 提高阈值只保留「被反复一起谈论」的关系 —— 这是 v1 唯一的收窄手段，必须真的管用；
- 主题名就是这页里的东西（关键词组合），不是编出来的标题。
"""

from __future__ import annotations

from app.rag.entities import EntityCluster, EntityMention, EntityRelation, relation_edges
from app.rag.topics import build_topics, topic_stats


def mention(name: str, claim_text: str, *, post_id: int = 7, chunk_index: int = 0) -> EntityMention:
    return EntityMention(
        name=name,
        normalized=name.casefold(),
        kind="concept",
        post_id=post_id,
        chunk_index=chunk_index,
        claim_text=claim_text,
    )


def cluster(name: str, mentions: list[EntityMention]) -> EntityCluster:
    return EntityCluster(name=name, normalized=name.casefold(), kind="concept", mentions=mentions)


def relation(source: str, target: str, weight: int, evidence: list[tuple[int, int, str]]):
    return EntityRelation(
        source=source.casefold(), target=target.casefold(), weight=weight, evidence=evidence
    )


def test_two_separate_groups_become_two_topics() -> None:
    clusters = [
        cluster("甲", [mention("甲", "甲和乙一起")]),
        cluster("乙", [mention("乙", "甲和乙一起")]),
        cluster("丙", [mention("丙", "丙和丁一起")]),
        cluster("丁", [mention("丁", "丙和丁一起")]),
    ]
    relations = [
        relation("甲", "乙", 1, [(7, 0, "甲和乙一起")]),
        relation("丙", "丁", 1, [(7, 1, "丙和丁一起")]),
    ]

    topics, notes = build_topics(clusters, relations)

    assert len(topics) == 2
    assert sorted(topic.size for topic in topics) == [2, 2]
    assert all(topic.weight == 1 for topic in topics)
    assert notes == [], "没有孤立实体就不该有提示"


def test_entities_without_edges_are_reported_separately() -> None:
    """孤立实体不是坏数据，但也不能有归属 —— 它只是还没被和别的东西一起谈论过。"""
    clusters = [
        cluster("甲", [mention("甲", "甲和乙一起")]),
        cluster("乙", [mention("乙", "甲和乙一起")]),
        cluster("孤零零", [mention("孤零零", "只提到孤零零")]),
    ]
    relations = [relation("甲", "乙", 1, [(7, 0, "甲和乙一起")])]

    topics, notes = build_topics(clusters, relations)

    assert len(topics) == 1
    assert "孤零零" not in topics[0].entities
    assert any("孤零零" in note for note in notes), "要如实说出它没被归入任何主题"


def test_transitive_chain_lands_in_one_topic() -> None:
    """甲—乙—丙 连成一条链 → 同一个主题（这正是「连成一片」的起点，如实接受）。"""
    clusters = [
        cluster("甲", [mention("甲", "甲乙")]),
        cluster("乙", [mention("乙", "甲乙")]),
        cluster("丙", [mention("丙", "乙丙")]),
    ]
    relations = [
        relation("甲", "乙", 1, [(7, 0, "甲乙")]),
        relation("乙", "丙", 1, [(7, 1, "乙丙")]),
    ]

    topics, _ = build_topics(clusters, relations)

    assert len(topics) == 1
    assert topics[0].size == 3
    assert topics[0].weight == 2, "主题权重是该主题内的边权和"
    assert topics[0].post_ids == [7]


def test_min_weight_narrows_the_topic() -> None:
    """提高阈值只保留「被反复一起谈论」的关系 —— v1 唯一的收窄手段必须真的管用。"""
    clusters = [
        cluster("甲", [mention("甲", "甲乙")]),
        cluster("乙", [mention("乙", "甲乙")]),
        cluster("丙", [mention("丙", "甲丙")]),
    ]
    relations = [
        relation("甲", "乙", 3, [(7, 0, "甲乙")]),
        relation("甲", "丙", 1, [(7, 1, "甲丙")]),
    ]

    loose, _ = build_topics(clusters, relations)
    strict, _ = build_topics(clusters, relations, min_weight=2)

    assert loose[0].size == 3
    assert strict[0].size == 2, "弱边被丢掉后，丙不再和甲同组"
    assert strict[0].entities and "丙" not in strict[0].entities


def test_order_is_deterministic_regardless_of_input_order() -> None:
    clusters = [
        cluster("甲", [mention("甲", "甲乙", chunk_index=0)]),
        cluster("乙", [mention("乙", "甲乙", chunk_index=1)]),
        cluster("丙", [mention("丙", "丙丁", chunk_index=2)]),
        cluster("丁", [mention("丁", "丙丁", chunk_index=3)]),
    ]
    relations = [
        relation("甲", "乙", 2, [(7, 0, "甲乙")]),
        relation("丙", "丁", 2, [(7, 2, "丙丁")]),
    ]

    first = [topic.to_dict() for topic in build_topics(clusters, relations)[0]]
    second = [
        topic.to_dict()
        for topic in build_topics(list(reversed(clusters)), list(reversed(relations)))[0]
    ]

    assert first == second, "输入顺序不该影响输出（否则页面 diff 里全是噪声）"


def test_topic_name_is_made_of_its_own_entities() -> None:
    clusters = [
        cluster("每天写五百字", [mention("每天写五百字", "每天写五百字可以累积")]),
        cluster("十八万字", [mention("十八万字", "每天写五百字可以累积")]),
    ]
    relations = [relation("每天写五百字", "十八万字", 1, [(7, 0, "每天写五百字可以累积")])]

    topic = build_topics(clusters, relations)[0][0]

    assert "每天写五百字" in topic.name
    view = topic.to_dict()
    assert view["keywords"] and set(view["keywords"]) <= set(topic.entities)
    assert topic.evidence == [(7, 0, "每天写五百字可以累积")], "主题页要带可核对的原文"


def test_max_topics_truncates_but_says_so() -> None:
    clusters = [
        cluster("甲", [mention("甲", "甲乙")]),
        cluster("乙", [mention("乙", "甲乙")]),
        cluster("丙", [mention("丙", "丙丁")]),
        cluster("丁", [mention("丁", "丙丁")]),
    ]
    relations = [
        relation("甲", "乙", 5, [(7, 0, "甲乙")]),
        relation("丙", "丁", 1, [(7, 1, "丙丁")]),
    ]

    topics, notes = build_topics(clusters, relations, max_topics=1)

    assert len(topics) == 1
    assert topics[0].weight == 5, "留下的是权重最高的那个"
    assert any("只回了权重最高的 1 个" in note for note in notes), "截断不能静默"


def test_stats_count_topics_by_size() -> None:
    clusters = [
        cluster("甲", [mention("甲", "甲乙")]),
        cluster("乙", [mention("乙", "甲乙")]),
        cluster("丙", [mention("丙", "丙丁")]),
        cluster("丁", [mention("丁", "丙丁")]),
    ]
    relations = [
        relation("甲", "乙", 1, [(7, 0, "甲乙")]),
        relation("丙", "丁", 1, [(7, 1, "丙丁")]),
    ]

    topics, _ = build_topics(clusters, relations)

    assert topic_stats(topics) == {"size2": 2}


def test_relation_edges_and_topics_compose() -> None:
    """端到端的小闭环：提及 → 共现边 → 主题（证明两层的字段是对得上的）。"""
    mentions = [
        mention("每天写五百字", "每天写五百字可以累积成十八万字"),
        mention("十八万字", "每天写五百字可以累积成十八万字"),
    ]
    clusters = [
        cluster("每天写五百字", [mentions[0]]),
        cluster("十八万字", [mentions[1]]),
    ]

    topics, _ = build_topics(clusters, relation_edges(mentions))

    assert len(topics) == 1
    assert topics[0].size == 2
    assert topics[0].evidence == [(7, 0, "每天写五百字可以累积成十八万字")]
