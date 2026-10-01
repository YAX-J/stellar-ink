"""增量失效盘点（E4-11）：文章改了，**只**让受影响的条目失效。

这一层的价值全在「三种状态分开」上，所以用例测的是：
* 段落哈希没变 → `current`（**不能**判成失效，否则每次盘点都逼着人做全量重建）；
* 段落还在但哈希变了 → `stale`，且**按文章**归并（重建的最小单位是文章，不是段落）；
* 段落没了 → `orphan`，且明确指出这些引文**无法再回到原文**；
* 哈希缺失（老数据）→ 按 `current` 处理（不能凭缺失判失效）；
* 判定**只看段落哈希**，不看主张文本 —— 文本是模型输出，重跑本来就可能变。
"""

from __future__ import annotations

from typing import Any

from app.rag.pipeline import IndexedChunk
from app.rag.staleness import stale_claims


def chunk(post_id: int, chunk_index: int, content_hash: str) -> IndexedChunk:
    return IndexedChunk(
        chunk_id=f"p{post_id}c{chunk_index}",
        post_id=post_id,
        title=f"文章 {post_id}",
        text="正文",
        payload={"chunkIndex": chunk_index, "contentHash": content_hash, "text": "正文"},
    )


def anchor(post_id: int, chunk_index: int, content_hash: str) -> Any:
    class _Anchor:
        pass

    item = _Anchor()
    item.post_id = post_id
    item.chunk_index = chunk_index
    item.content_hash = content_hash
    return item


def test_unchanged_chunks_stay_current() -> None:
    report = stale_claims(
        [chunk(7, 0, "hash0"), chunk(7, 1, "hash1")],
        [anchor(7, 0, "hash0"), anchor(7, 1, "hash1")],
    )

    assert (report.checked, report.current, report.stale, report.orphan) == (2, 2, 0, 0)
    assert report.stale_post_ids == []
    assert "不需要重建" in report.notes()[0]


def test_changed_chunk_marks_the_post_stale() -> None:
    """段落内容变了 → 该文章需要重建；**按文章**归并（重建的最小单位是文章）。"""
    report = stale_claims(
        [chunk(7, 0, "hash0-new"), chunk(7, 1, "hash1")],
        [anchor(7, 0, "hash0"), anchor(7, 1, "hash1"), anchor(7, 0, "hash0")],
    )

    assert report.stale == 2, "两条主张都锚在变过的那段上"
    assert report.current == 1
    assert report.stale_post_ids == [7], "按文章归并，不是每段一条"


def test_missing_chunk_is_orphan_and_explained() -> None:
    """段落没了（文章删了或删短了）→ 引文必然失效，必须清理。"""
    report = stale_claims([chunk(7, 0, "hash0")], [anchor(7, 0, "hash0"), anchor(7, 9, "hash9")])

    assert report.orphan == 1
    assert report.orphan_post_ids == [7]
    assert report.stale_post_ids == [], "孤立段落不等于「内容变了」—— 处置不一样"
    assert any("无法再回到原文" in note for note in report.notes())


def test_missing_hash_is_treated_as_current() -> None:
    """哈希缺失（老数据/列没填）**不能**判失效：那会把整库判成过期，逼着人做全量重建。"""
    report = stale_claims([chunk(7, 0, "")], [anchor(7, 0, "hash0")])

    assert report.current == 1
    assert report.stale == 0


def test_claims_without_matching_post_are_orphan() -> None:
    report = stale_claims([chunk(7, 0, "hash0")], [anchor(9, 0, "hash0")])

    assert report.orphan == 1
    assert report.orphan_post_ids == [9]


def test_report_shape_matches_the_contract() -> None:
    report = stale_claims(
        [chunk(7, 0, "new")],
        [anchor(7, 0, "old"), anchor(7, 5, "gone")],
    )

    payload = report.to_dict()

    assert set(payload) == {
        "checked",
        "current",
        "stale",
        "orphan",
        "stalePostIds",
        "orphanPostIds",
        "notes",
    }, "契约里是驼峰；两侧测试都读它"
    assert payload["stalePostIds"] == [7]
    assert payload["orphanPostIds"] == [7]
    assert payload["notes"], "状态的含义必须写出来，否则「stale 1」没人知道该干什么"


def test_order_is_deterministic() -> None:
    chunks = [chunk(7, 0, "a"), chunk(9, 0, "b")]
    stored = [anchor(9, 0, "old"), anchor(7, 0, "old")]

    first = stale_claims(chunks, stored).to_dict()
    second = stale_claims(list(reversed(chunks)), list(reversed(stored))).to_dict()

    assert first == second
