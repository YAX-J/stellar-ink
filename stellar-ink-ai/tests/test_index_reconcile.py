"""增量索引的判定（M4，`app/rag/index_reconcile.py`）。

这一层要守的核心是那条**失败方式不对称**的判断：

* 误判「变了」→ 多花一次嵌入钱，用户看不出来；
* 误判「没变」→ **改动永远进不了索引**，而且不报错 —— 检索到旧内容看起来只是
  「答案有点过时」，等发现时已经检索了很久的旧版本。

所以「拿不到哈希」一律按「变了」处理，并且**把这类文档单独数出来**（好排查为什么每次都全量）。

另一条同样要守的是**文档标识**：文章 3 与笔记 3 是两个文档（`(kind, id)`）。
按裸数字 id 归并的后果与上面那条一样安静 —— 只不过程序不报错、内容也不旧，
而是「该重建的没重建、该删的没删」。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from app.rag.index_reconcile import ReconcilePlan, hashes_by_doc, reconcile

#: 文档标识的简写（测试里到处要用，写全 `("post", 1)` 会把断言淹掉）
POST = "post"
NOTE = "note"


def _key(content_id: int, kind: str = POST) -> tuple[str, int]:
    return (kind, content_id)


@dataclass
class _Chunk:
    """最小子块：对账只用到 kind / post_id 与 payload 里的 contentHash。"""

    post_id: int
    content_hash: str | None = "h1"
    payload: dict[str, str] = field(default_factory=dict)
    kind: str = POST

    def __post_init__(self) -> None:
        if not self.payload:
            self.payload = {} if self.content_hash is None else {"contentHash": self.content_hash}


def _chunk(post_id: int, content_hash: str | None, *, kind: str = POST) -> _Chunk:
    return _Chunk(post_id=post_id, content_hash=content_hash, kind=kind)


def test_unchanged_docs_are_skipped() -> None:
    """增量的意义就是省下这些嵌入：内容一致就不重建。"""
    chunks = [_chunk(1, "a"), _chunk(2, "b")]
    indexed = {_key(1): ["a"], _key(2): ["b"]}

    plan = reconcile(chunks, indexed)

    assert plan.unchanged == [_key(1), _key(2)]
    assert plan.changed == []
    assert plan.removed == []
    assert any("跳过重建" in note for note in plan.notes)


def test_changed_content_is_rebuilt() -> None:
    chunks = [_chunk(1, "new")]
    indexed = {_key(1): ["old"]}

    plan = reconcile(chunks, indexed)

    assert plan.changed == [_key(1)]
    assert plan.unchanged == []


def test_new_doc_is_rebuilt() -> None:
    """语料里有、索引里没有 → 新内容，要重建。"""
    plan = reconcile([_chunk(1, "a")], {})

    assert plan.changed == [_key(1)]
    assert plan.unverifiable == [], "「索引里没有」是新文档，不是读不到哈希"


def test_missing_hash_in_index_counts_as_changed_and_is_flagged() -> None:
    """**关键口径**：索引里读不到哈希时按「变了」处理，并单独数出来。

    反过来假设「没变」的后果是「改动永远不进索引」，而且不会报错。
    """
    plan = reconcile([_chunk(1, "a"), _chunk(2, "b")], {_key(1): None, _key(2): ["b"]})

    assert plan.changed == [_key(1)]
    assert plan.unverifiable == [_key(1)]
    assert plan.unchanged == [_key(2)]
    assert any("读不到段落哈希" in note for note in plan.notes)


def test_chunk_without_hash_in_corpus_is_ignored_not_guessed() -> None:
    """语料侧的哈希缺失：不猜（那篇不进任何清单），因为它连「当前内容」都说不清。"""
    plan = reconcile([_chunk(1, None)], {})

    assert plan.changed == []
    assert plan.unchanged == []
    assert hashes_by_doc([_chunk(1, None)]) == {}


def test_multi_chunk_doc_compares_the_whole_set() -> None:
    """一篇多段：只要有一段变了就要重建整篇（段落之间是同一篇内容的一部分）。"""
    same = [_chunk(1, "a"), _chunk(1, "b")]
    assert reconcile(same, {_key(1): ["a", "b"]}).unchanged == [_key(1)]

    one_changed = [_chunk(1, "a"), _chunk(1, "c")]
    assert reconcile(one_changed, {_key(1): ["a", "b"]}).changed == [_key(1)]

    added_chunk = [_chunk(1, "a"), _chunk(1, "b"), _chunk(1, "c")]
    assert reconcile(added_chunk, {_key(1): ["a", "b"]}).changed == [_key(1)], "多了一段也要重建"

    removed_chunk = [_chunk(1, "a")]
    assert reconcile(removed_chunk, {_key(1): ["a", "b"]}).changed == [_key(1)], "少了一段也要重建"


def test_removed_docs_are_reported_for_deletion() -> None:
    plan = reconcile([_chunk(1, "a")], {_key(1): ["a"], _key(9): ["z"]})

    assert plan.removed == [_key(9)]
    assert plan.changed == []
    assert any("已从语料里消失" in note for note in plan.notes)


def test_empty_corpus_removes_everything() -> None:
    """语料空了（博客被清空）也要把索引清干净，而不是留一堆再也检索不到的片段。"""
    plan = reconcile([], {_key(1): ["a"], _key(2): ["b"]})

    assert plan.removed == [_key(1), _key(2)]
    assert plan.changed == []


def test_plan_is_sorted_and_reproducible() -> None:
    """结果必须确定：同一个输入两次得到同一份计划（否则「增量」无法解释也无法对比）。"""
    chunks = [_chunk(3, "c"), _chunk(1, "a"), _chunk(2, "b")]
    plan = reconcile(chunks, {})

    assert plan.changed == [_key(1), _key(2), _key(3)]
    assert reconcile(chunks, {}).to_dict() == plan.to_dict()


def test_steady_state_says_so() -> None:
    plan = reconcile([_chunk(1, "a")], {_key(1): ["a"]})

    assert any("稳态" in note for note in plan.notes), (
        "一致时要明说「不用重建」，而不是给一份空计划"
    )


def test_rebuild_count_is_reported_for_the_job() -> None:
    """任务报告要能用它算「这次比全量省了多少」。"""
    plan: ReconcilePlan = reconcile([_chunk(1, "a"), _chunk(2, "b")], {_key(1): ["a"]})

    assert plan.rebuild_count == 1
    assert plan.to_dict()["rebuildCount"] == 1


def test_same_id_in_different_kinds_are_different_documents() -> None:
    """文章 3 与笔记 3 是两个文档：改一个不该牵连另一个。

    这是 `(kind, id)` 这个标识存在的全部理由 —— 按裸数字 id 归并，
    「重建文章 3」会把笔记 3 的哈希当成自己的，于是漏掉真正该重建的那一篇，
    而现象只是「答案有点旧」。
    """
    chunks = [_chunk(3, "post-new"), _chunk(3, "note-same", kind=NOTE)]
    indexed = {_key(3): ["post-old"], _key(3, NOTE): ["note-same"]}

    plan = reconcile(chunks, indexed)

    assert plan.changed == [_key(3)]
    assert plan.unchanged == [_key(3, NOTE)]
    assert plan.removed == []


def test_note_removed_upstream_is_deleted() -> None:
    """笔记转私有后从语料里消失，索引里那一份要被删掉（隐私保证的落点）。"""
    plan = reconcile([_chunk(1, "a")], {_key(1): ["a"], _key(11, NOTE): ["z"]})

    assert plan.removed == [_key(11, NOTE)]


def test_to_dict_renders_keys_as_readable_text() -> None:
    """响应里的标识写成 `post:3`：嵌套数组在日志与界面上都难读。"""
    plan = reconcile([_chunk(3, "a"), _chunk(11, "b", kind=NOTE)], {})

    assert plan.to_dict()["changed"] == ["note:11", "post:3"]
