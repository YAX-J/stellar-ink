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
