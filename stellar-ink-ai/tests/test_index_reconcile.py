"""增量索引的判定（M4，`app/rag/index_reconcile.py`）。

这一层要守的核心是那条**失败方式不对称**的判断：

* 误判「变了」→ 多花一次嵌入钱，用户看不出来；
* 误判「没变」→ **改动永远进不了索引**，而且不报错 —— 检索到旧内容看起来只是
  「答案有点过时」，等发现时已经检索了很久的旧版本。

所以「拿不到哈希」一律按「变了」处理，并且**把这类文章单独数出来**（好排查为什么每次都全量）。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from app.rag.index_reconcile import ReconcilePlan, hashes_by_post, reconcile


@dataclass
class _Chunk:
    """最小子块：对账只用到 post_id 与 payload 里的 contentHash。"""

    post_id: int
    content_hash: str | None = "h1"
    payload: dict[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.payload:
            self.payload = {} if self.content_hash is None else {"contentHash": self.content_hash}


def _chunk(post_id: int, content_hash: str | None) -> _Chunk:
    return _Chunk(post_id=post_id, content_hash=content_hash)


def test_unchanged_posts_are_skipped() -> None:
    """增量的意义就是省下这些嵌入：内容一致就不重建。"""
    chunks = [_chunk(1, "a"), _chunk(2, "b")]
    indexed = {1: ["a"], 2: ["b"]}

    plan = reconcile(chunks, indexed)

    assert plan.unchanged == [1, 2]
    assert plan.changed == []
    assert plan.removed == []
    assert any("跳过重建" in note for note in plan.notes)


def test_changed_content_is_rebuilt() -> None:
    chunks = [_chunk(1, "new")]
    indexed = {1: ["old"]}

    plan = reconcile(chunks, indexed)

    assert plan.changed == [1]
    assert plan.unchanged == []


def test_new_post_is_rebuilt() -> None:
    """语料里有、索引里没有 → 新文章，要重建。"""
    plan = reconcile([_chunk(1, "a")], {})

    assert plan.changed == [1]
    assert plan.unverifiable == [], "「索引里没有」是新文章，不是读不到哈希"


def test_missing_hash_in_index_counts_as_changed_and_is_flagged() -> None:
    """**关键口径**：索引里读不到哈希时按「变了」处理，并单独数出来。

    反过来假设「没变」的后果是「改动永远不进索引」，而且不会报错。
    """
    plan = reconcile([_chunk(1, "a"), _chunk(2, "b")], {1: None, 2: ["b"]})

    assert plan.changed == [1]
    assert plan.unverifiable == [1]
    assert plan.unchanged == [2]
    assert any("读不到段落哈希" in note for note in plan.notes)


def test_chunk_without_hash_in_corpus_is_ignored_not_guessed() -> None:
    """语料侧的哈希缺失：不猜（那篇不进任何清单），因为它连「当前内容」都说不清。"""
    plan = reconcile([_chunk(1, None)], {})

    assert plan.changed == []
    assert plan.unchanged == []
    assert hashes_by_post([_chunk(1, None)]) == {}


def test_multi_chunk_post_compares_the_whole_set() -> None:
    """一篇多段：只要有一段变了就要重建整篇（段落之间是同一篇文章的一部分）。"""
    same = [_chunk(1, "a"), _chunk(1, "b")]
    assert reconcile(same, {1: ["a", "b"]}).unchanged == [1]

    one_changed = [_chunk(1, "a"), _chunk(1, "c")]
    assert reconcile(one_changed, {1: ["a", "b"]}).changed == [1]

    added_chunk = [_chunk(1, "a"), _chunk(1, "b"), _chunk(1, "c")]
    assert reconcile(added_chunk, {1: ["a", "b"]}).changed == [1], "多了一段也要重建"

    removed_chunk = [_chunk(1, "a")]
    assert reconcile(removed_chunk, {1: ["a", "b"]}).changed == [1], "少了一段也要重建"


def test_removed_posts_are_reported_for_deletion() -> None:
    plan = reconcile([_chunk(1, "a")], {1: ["a"], 9: ["z"]})

    assert plan.removed == [9]
    assert plan.changed == []
    assert any("已从语料里消失" in note for note in plan.notes)


def test_empty_corpus_removes_everything() -> None:
    """语料空了（博客被清空）也要把索引清干净，而不是留一堆再也检索不到的片段。"""
    plan = reconcile([], {1: ["a"], 2: ["b"]})

    assert plan.removed == [1, 2]
    assert plan.changed == []


def test_plan_is_sorted_and_reproducible() -> None:
    """结果必须确定：同一个输入两次得到同一份计划（否则「增量」无法解释也无法对比）。"""
    chunks = [_chunk(3, "c"), _chunk(1, "a"), _chunk(2, "b")]
    plan = reconcile(chunks, {})

    assert plan.changed == [1, 2, 3]
    assert reconcile(chunks, {}).to_dict() == plan.to_dict()


def test_steady_state_says_so() -> None:
    plan = reconcile([_chunk(1, "a")], {1: ["a"]})

    assert any("稳态" in note for note in plan.notes), (
        "一致时要明说「不用重建」，而不是给一份空计划"
    )


def test_rebuild_count_is_reported_for_the_job() -> None:
    """任务报告要能用它算「这次比全量省了多少」。"""
    plan: ReconcilePlan = reconcile([_chunk(1, "a"), _chunk(2, "b")], {1: ["a"]})

    assert plan.rebuild_count == 1
    assert plan.to_dict()["rebuildCount"] == 1
