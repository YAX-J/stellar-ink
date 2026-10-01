"""实体抽取与别名合并（E4-4）：**实体也必须站在留下来的主张上**。

为什么这条规则要紧：实体不是从文章里自由捞出来的名词，它是后面「关系」与「主题页面」的骨架。
如果实体可以脱离证据存在，那么图上的每个节点都可能来自一句模型编的话 ——
而图这种东西**看起来最像真的**：有名字、有连线、有出处字样，唯独无从核对。

所以这里逐条测的是：实体必须能在某条**通过校验的主张**或它的原文片段里逐字找到；
只出现在被丢弃主张里的实体，必须跟着一起消失。
"""

from __future__ import annotations

from collections import Counter

import pytest

from app.rag.entities import (
    DROP_ENTITY_NOT_IN_TEXT,
    DROP_ENTITY_TOO_LONG,
    DROP_ENTITY_TOO_SHORT,
    collect_entities,
    entity_kind,
    merge_entities,
    normalize_entity,
    relation_edges,
)
from app.rag.wiki import WikiClaim


def claim(text: str, quote: str, *, chunk_index: int = 0, post_id: int = 7) -> WikiClaim:
    return WikiClaim(
        text=text,
        post_id=post_id,
        chunk_index=chunk_index,
        post_version="v1",
        content_hash=f"hash{chunk_index}",
        quote=quote,
    )


def test_normalize_merges_writing_differences() -> None:
    """全角/半角、空白、大小写、首尾标点都要归到同一个键（写法差异不是不同实体）。"""
    assert normalize_entity("Stellar  Ink") == normalize_entity("ＳＴＥＬＬＡＲINK")
    assert normalize_entity("「星笺」") == normalize_entity("星笺")
    assert normalize_entity(" 每天写五百字 ") == normalize_entity("每天写五百字")
    assert normalize_entity("星笺,") == normalize_entity("星笺。")


def test_entity_kind_falls_back_to_other() -> None:
    """类型写错不该让实体消失：未知类型归到 other（它本身仍然有用）。"""
    assert entity_kind("concept") == "concept"
    assert entity_kind("Person") == "person"
    assert entity_kind("宇宙飞船") == "other"
    assert entity_kind(None) == "other"


def test_entity_must_appear_in_a_kept_claim() -> None:
    claims = [claim("每天写五百字可以累积成十八万字", "每天写五百字，一年就是十八万字")]
    dropped: Counter[str] = Counter()

    kept = collect_entities(
        [
            {"name": "每天写五百字", "kind": "concept"},  # 在主张里
            {"name": "十八万字", "kind": "concept"},  # 只在原文片段里也算
            {"name": "番茄工作法", "kind": "concept"},  # 两处都没有 → 编的
        ],
        post_id=7,
        claims=claims,
        dropped=dropped,
    )

    assert [mention.name for mention in kept] == ["每天写五百字", "十八万字"]
    assert dropped == {DROP_ENTITY_NOT_IN_TEXT: 1}


def test_entity_from_a_dropped_claim_disappears_with_it() -> None:
    """**这条最关键**：被丢弃主张里的实体必须一起消失，否则图会挂上不存在的东西。"""
    dropped: Counter[str] = Counter()

    kept = collect_entities(
        [{"name": "完全断网", "kind": "concept"}],
        post_id=7,
        claims=[],  # 本文没有任何主张通过校验
        dropped=dropped,
    )

    assert kept == []
    assert dropped == {DROP_ENTITY_NOT_IN_TEXT: 1}


def test_no_claims_and_no_entities_leaves_no_phantom_count() -> None:
    """没有实体时不该在统计里留下一个 0 值键 —— 那会让人以为发生过这件事。"""
    dropped: Counter[str] = Counter()

    collect_entities([], post_id=7, claims=[], dropped=dropped)

    assert dropped == {}


def test_length_bounds_and_kind_are_recorded() -> None:
    claims = [claim("甲乙丙丁戊己庚辛", "甲乙丙丁戊己庚辛")]
    dropped: Counter[str] = Counter()

    kept = collect_entities(
        [
            {"name": "甲", "kind": "concept"},  # 太短
            {"name": "很长" * 40, "kind": "concept"},  # 太长
            {"name": "甲乙", "kind": "工具"},  # 类型不认识 → other
        ],
        post_id=7,
        claims=claims,
        dropped=dropped,
    )

    assert len(kept) == 1
    assert kept[0].kind == "other"
    assert dropped == {DROP_ENTITY_TOO_SHORT: 1, DROP_ENTITY_TOO_LONG: 1}


def test_merge_groups_by_normalized_and_keeps_evidence_lines() -> None:
    claims = [claim("每天写五百字可以累积成十八万字", "每天写五百字")]
    mentions = collect_entities(
        [
            {"name": "每天写五百字", "kind": "concept"},
            # 首尾空白与全角空格：写法不同，实体相同
            {"name": "　每天写五百字 ", "kind": "concept"},
        ],
        post_id=7,
        claims=claims,
        dropped=Counter(),
    )

    clusters = merge_entities(mentions)

    assert len(clusters) == 1
    assert clusters[0].count == 2
    assert clusters[0].kind == "concept"
    # 每个提及都带着「挂在哪条主张上」——实体回到证据的那条线
    assert all(item.claim_text for item in clusters[0].mentions)
    assert clusters[0].to_dict()["postIds"] == [7]


def test_merge_is_deterministic_and_sorted_by_count() -> None:
    """顺序必须确定：否则同一份数据两次构建会得到不同顺序的页面，diff 里全是噪声。"""
    claims = [claim("甲乙丙丁戊己庚辛", "甲乙丙丁戊己庚辛")]
    many = collect_entities(
        [{"name": "甲乙", "kind": "concept"} for _ in range(3)],
        post_id=7,
        claims=claims,
        dropped=Counter(),
    )
    few = collect_entities(
        [{"name": "丙丁", "kind": "concept"}],
        post_id=9,
        claims=[claim("丙丁戊己庚辛壬癸", "丙丁戊己庚辛壬癸", post_id=9)],
        dropped=Counter(),
    )

    first = [(c.normalized, c.count) for c in merge_entities(many + few)]
    second = [(c.normalized, c.count) for c in merge_entities(few + many)]

    assert first == second, "输入顺序不该影响输出顺序"
    assert first[0][0] == "甲乙", "出现次数多的排在前面"


@pytest.mark.parametrize("spelling", ["每天写五百字", "　每天写五百字 ", "每天写五百字。"])
def test_spelling_variants_merge_into_one_cluster(spelling: str) -> None:
    claims = [claim("每天写五百字可以累积成十八万字", "每天写五百字")]
    mentions = collect_entities(
        [{"name": "每天写五百字", "kind": "concept"}, {"name": spelling, "kind": "concept"}],
        post_id=7,
        claims=claims,
        dropped=Counter(),
    )

    assert len(merge_entities(mentions)) == 1


# ------------------------------------------------------------------ 共现关系


def _two_entities_in_one_claim() -> list:
    """一句主张里同时出现两个实体 —— 共现关系的来源。"""
    claims = [claim("每天写五百字可以累积成十八万字", "每天写五百字，一年就是十八万字")]
    return collect_entities(
        [{"name": "每天写五百字", "kind": "concept"}, {"name": "十八万字", "kind": "concept"}],
        post_id=7,
        claims=claims,
        dropped=Counter(),
    )


def test_co_occurrence_creates_one_undirected_edge() -> None:
    relations = relation_edges(_two_entities_in_one_claim())

    assert len(relations) == 1
    edge = relations[0]
    # 无向边只有一种表示：否则 (A,B) 与 (B,A) 各存一行，权重看起来只有实际的一半
    assert edge.source < edge.target
    assert edge.weight == 1


def test_edge_carries_its_evidence() -> None:
    """边必须能回到原文：哪篇文章的哪句话同时提到了这两个实体。"""
    edge = relation_edges(_two_entities_in_one_claim())[0]

    assert edge.evidence == [(7, 0, "每天写五百字可以累积成十八万字")]
    assert edge.to_dict()["evidence"][0]["claimText"] == "每天写五百字可以累积成十八万字"


def test_entities_in_different_claims_are_not_connected() -> None:
    """没在同一句里出现过就不该连边 —— 否则图会连成一片，等于什么都没说。"""
    mentions = collect_entities(
        [{"name": "甲概念", "kind": "concept"}],
        post_id=7,
        claims=[claim("甲概念很重要", "甲概念很重要")],
        dropped=Counter(),
    ) + collect_entities(
        [{"name": "乙概念", "kind": "concept"}],
        post_id=8,
        claims=[claim("乙概念也很重要", "乙概念也很重要", post_id=8)],
        dropped=Counter(),
    )

    assert relation_edges(mentions) == []


def test_repeated_co_occurrence_raises_weight() -> None:
    """同两个实体在两句话里都出现 → 权重 2、证据两条（「被一起谈论的程度」）。"""
    claim_a = claim("每天写五百字可以累积成十八万字", "每天写五百字")
    # ⚠️ 第二句必须**同时**含这两个实体，否则测的就不是共现（第一版这里漏了一个字，
    # 于是用例红了 —— 红的是测试数据，不是实现）
    claim_b = claim("十八万字来自每天写五百字的复利", "十八万字", chunk_index=1)
    separate = collect_entities(
        [{"name": "每天写五百字", "kind": "concept"}],
        post_id=7,
        claims=[claim_a],
        dropped=Counter(),
    ) + collect_entities(
        [{"name": "十八万字", "kind": "concept"}],
        post_id=7,
        claims=[claim_b],
        dropped=Counter(),
    )

    # 两个实体分别在两条主张里各出现一次 —— 没有共现，就不该连边
    assert relation_edges(separate) == []

    both = collect_entities(
        [{"name": "每天写五百字", "kind": "concept"}, {"name": "十八万字", "kind": "concept"}],
        post_id=7,
        claims=[claim_a, claim_b],
        dropped=Counter(),
    )
    edges = relation_edges(both)
    assert len(edges) == 1, "两句里都同时出现，仍然只有一条边"
    assert edges[0].weight == 2, "权重是共同出现的主张条数"
    assert len(edges[0].evidence) == 2


def test_relation_order_is_deterministic() -> None:
    mentions = _two_entities_in_one_claim()
    first = [(r.source, r.target, r.weight) for r in relation_edges(mentions)]
    second = [(r.source, r.target, r.weight) for r in relation_edges(list(reversed(mentions)))]

    assert first == second, "输入顺序不该影响输出顺序"
