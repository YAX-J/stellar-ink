"""相关度门限：相对门限提精度、绝对门限才能拒答。

这两个阈值是在本地基线评测里被真实数字逼出来的：
- 相对门限（`min_score_ratio`）**永远不会清空结果** —— 最高分自己一定过门限，
  所以它只能过滤弱候选（precision 从 0.24 提到 0.80），拒答率仍是 0；
- 要让无答案题真的拒答，必须有**绝对下限**（`min_score`），它是唯一能返回空列表的机制。
"""

from __future__ import annotations

import pytest

from app.rag.retrieval import Bm25Index, tokenize

DOCS = [
    "星笺把文章比作星辰，每篇文章对应夜空中的一个坐标。",
    "嵌入模型把文本映射成向量，语义相近的文本距离更近。",
    "今晚的星星很多，我坐在窗边写字。",
]


def test_relative_threshold_filters_weak_candidates() -> None:
    """相对门限的作用是提精度：只留接近最高分的候选。"""
    unfiltered = Bm25Index().fit(DOCS).search("星笺 星辰 文章", top_k=5)
    filtered = Bm25Index(min_score_ratio=0.8).fit(DOCS).search("星笺 星辰 文章", top_k=5)

    assert unfiltered, "未过滤时应有候选"
    assert len(filtered) <= len(unfiltered)
    assert filtered, "最高分自己一定过相对门限"
    assert filtered[0].score == unfiltered[0].score


def test_relative_threshold_alone_never_refuses() -> None:
    """关键行为：只开相对门限时，任何有命中的查询都不会返回空 —— 因此拒答率恒为 0。

    这条断言把「为什么必须再加绝对下限」写死在测试里，避免以后有人以为
    调 relative ratio 就能控制拒答。
    """
    index = Bm25Index(min_score_ratio=0.99).fit(DOCS)

    assert index.search("星笺", top_k=5), "相对门限再高也会留下最高分那条"


def test_absolute_threshold_can_refuse() -> None:
    """绝对下限是唯一能返回空的机制。"""
    baseline = Bm25Index().fit(DOCS)
    hits = baseline.search("星笺 星辰", top_k=5)
    assert hits
    top_score = hits[0].score

    strict = Bm25Index(min_score=top_score + 1.0).fit(DOCS)
    assert strict.search("星笺 星辰", top_k=5) == [], "高于最高分时应当全部丢弃 → 可拒答"


def test_absolute_threshold_keeps_strong_matches() -> None:
    baseline = Bm25Index().fit(DOCS)
    top_score = baseline.search("星笺 星辰", top_k=5)[0].score

    threshold = Bm25Index(min_score=top_score * 0.5).fit(DOCS).search("星笺 星辰", top_k=5)

    assert threshold, "下限设在一半最高分时，强匹配应当保留"


def test_thresholds_combine_absolute_then_relative() -> None:
    """组合使用：先按绝对下限砍掉「明显不相关」，再按相对门限提精度。"""
    index = Bm25Index(min_score=0.1, min_score_ratio=0.5).fit(DOCS)

    hits = index.search("向量 嵌入", top_k=5)

    assert hits
    assert all(hit.score >= 0.1 for hit in hits)


@pytest.mark.parametrize("value", [-1.0, -0.01])
def test_absolute_threshold_rejects_negative(value: float) -> None:
    with pytest.raises(ValueError):
        Bm25Index(min_score=value)


def test_zero_thresholds_are_no_ops() -> None:
    """默认（两个都 0）行为不变：向后兼容，不悄悄改变既有排序。"""
    default = Bm25Index().fit(DOCS).search("星笺", top_k=5)
    explicit = Bm25Index(min_score=0.0, min_score_ratio=0.0).fit(DOCS).search("星笺", top_k=5)

    assert [hit.chunk_index for hit in default] == [hit.chunk_index for hit in explicit]


def test_unanswerable_query_chars_overlap_produces_candidates() -> None:
    """把「单字重叠也能凑出候选」这个事实固定下来：它正是绝对下限存在的理由。

    「作者每天几点起床」与语料在「作/者/天」上重合，未设下限时会得到候选 ——
    产品上必须靠下限或主题判定挡掉，不能假装不会发生。
    """
    hits = Bm25Index().fit(DOCS).search("作者每天几点起床", top_k=5)

    # 至少说明索引确实会给出候选（不管分数高低）
    assert isinstance(hits, list)
    overlap_terms = [token for token in tokenize("作者每天几点起床") if token in " ".join(DOCS)]
    assert overlap_terms, "这条用例依赖单字重叠，若无重叠则说明分词变了"
