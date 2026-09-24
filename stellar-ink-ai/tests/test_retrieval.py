"""检索纯算法：分词、BM25、RRF 融合、Dense 余弦、混合开关。

这层是「多路召回 vs 单路召回」能不能在前端对比的基础，因此每条排序行为都要固定：
排序错了在产品上只表现为「答案不够准」，很难归因，只能靠测试钉住。
"""

from __future__ import annotations

import pytest

from app.rag.retrieval import (
    Bm25Index,
    ScoredChunk,
    dense_scores,
    hybrid_search,
    reciprocal_rank_fusion,
    tokenize,
)

DOCS = [
    "星笺把文章比作星辰，每篇文章对应夜空中的一个坐标。",  # 0 星图/坐标
    "嵌入模型把文本映射成向量，语义相近的文本距离更近。",  # 1 向量
    "BM25 擅长专有名词与原句匹配，Dense 擅长同义表达。",  # 2 检索算法
    "今天的晚饭是番茄炒蛋，与写作无关。",  # 3 无关
    "Qdrant 使用 HNSW 做近似最近邻检索。",  # 4 向量库
]


def test_tokenize_keeps_cjk_unigrams_and_bigrams() -> None:
    tokens = tokenize("星图")

    assert "星" in tokens and "图" in tokens, "单字必须保留：否则会漏召回"
    assert "星图" in tokens, "bigram 提供词序信息，避免「星图」与「图星」等价"


def test_tokenize_lowercases_latin_and_keeps_model_names_intact() -> None:
    tokens = tokenize("bge-m3 与 Qdrant，还有 GPT-4")

    assert "bge-m3" in tokens, "模型名里的连字符不能被切碎"
    assert "qdrant" in tokens, "拉丁词统一小写，BM25 与 bm25 应命中同一批文档"
    assert "gpt-4" in tokens


def test_tokenize_drops_stopwords_but_not_content_words() -> None:
    tokens = tokenize("这是关于检索的说明")

    assert "的" not in tokens and "是" not in tokens
    assert "检" in tokens and "索" in tokens


def test_tokenize_is_deterministic() -> None:
    text = "把文章写成星图 BM25 RRF"

    assert tokenize(text) == tokenize(text)
    assert tokenize("") == []


def test_bm25_ranks_relevant_document_first() -> None:
    index = Bm25Index().fit(DOCS)

    hits = index.search("星图 坐标", top_k=3)

    assert hits, "应当有命中"
    assert hits[0].chunk_index == 0
    assert hits[0].score > 0
    assert hits[0].source == "sparse"


def test_bm25_matches_exact_terms_that_dense_would_miss() -> None:
    """BM25 的价值在于专有名词精确匹配（这也是混合检索的理由）。"""
    index = Bm25Index().fit(DOCS)

    hits = index.search("HNSW 近似最近邻", top_k=3)

    assert hits and hits[0].chunk_index == 4


def test_bm25_returns_nothing_for_unrelated_query() -> None:
    """完全不相干的查询不该有命中。

    注意用字要真的互不重叠：中文单字索引下，「量子」的「子」会撞上语料里的「子」，
    所以这里选一批语料里没出现过的字（火山喷发/地震）。
    """
    index = Bm25Index().fit(DOCS)

    assert index.search("火山喷发与地震", top_k=5) == []


def test_bm25_results_are_stable_for_ties() -> None:
    """同分时按下标升序：否则每次跑评测的顺序都可能不同，无法对比。"""
    docs = ["星图", "星图", "星图"]

    hits = Bm25Index().fit(docs).search("星图", top_k=3)

    assert [hit.chunk_index for hit in hits] == [0, 1, 2]
    assert hits[0].score == hits[1].score == hits[2].score


def test_bm25_refit_replaces_previous_corpus() -> None:
    index = Bm25Index().fit(DOCS)
    index.fit(["只保留这一篇讲星图的文章"])

    assert index.size == 1
    hits = index.search("向量 嵌入", top_k=3)
    assert hits == [], "重建后不该再命中旧语料"


def test_bm25_respects_top_k_and_idf_weighting() -> None:
    index = Bm25Index().fit(DOCS)

    hits = index.search("检索", top_k=2)

    assert len(hits) <= 2
    # 稀有词（只在一两篇里出现）应当比常见词权重更高
    rare = index.search("HNSW", top_k=1)
    common = index.search("的", top_k=1)
    assert rare and not common, "停用词不该产生命中"


@pytest.mark.parametrize(("k1", "b"), [(0, 0.5), (-1, 0.5), (1.5, -0.1), (1.5, 1.1)])
def test_bm25_rejects_invalid_parameters(k1: float, b: float) -> None:
    with pytest.raises(ValueError):
        Bm25Index(k1=k1, b=b)


def test_dense_scores_are_cosine_and_sorted() -> None:
    query = [1.0, 0.0]
    vectors = [[1.0, 0.0], [0.0, 1.0], [0.7, 0.7]]

    hits = dense_scores(query, vectors, top_k=3)

    assert [hit.chunk_index for hit in hits] == [0, 2, 1]
    assert hits[0].score == pytest.approx(1.0)
    assert hits[1].score == pytest.approx(2**-0.5, rel=1e-3)
    assert hits[2].score == pytest.approx(0.0)
    assert all(hit.source == "dense" for hit in hits)


def test_dense_scores_reject_dimension_mismatch() -> None:
    """维度不一致说明查询与索引用的不是同一个嵌入模型：必须报错，不能静默跳过。"""
    with pytest.raises(ValueError, match="维度不一致"):
        dense_scores([1.0, 0.0], [[1.0, 0.0, 0.0]])


def test_dense_scores_handle_empty_input() -> None:
    assert dense_scores([1.0], []) == []


def test_rrf_fuses_by_rank_not_by_score_scale() -> None:
    """RRF 只看名次：因此 BM25 分数与余弦相似度可以直接混用，不需要归一化。"""
    sparse = [ScoredChunk(0, 12.5, "sparse"), ScoredChunk(1, 9.0, "sparse")]
    dense = [ScoredChunk(1, 0.93, "dense"), ScoredChunk(2, 0.88, "dense")]

    fused = reciprocal_rank_fusion([sparse, dense], top_k=3)

    # 文档 1 在两路都靠前，融合后应当第一
    assert fused[0].chunk_index == 1
    assert fused[0].source == "fused"
    assert {hit.chunk_index for hit in fused} == {0, 1, 2}


def test_rrf_weights_can_emphasise_one_route() -> None:
    sparse = [ScoredChunk(0, 1.0, "sparse")]
    dense = [ScoredChunk(1, 1.0, "dense")]

    sparse_first = reciprocal_rank_fusion([sparse, dense], weights=[1.0, 0.0])
    dense_first = reciprocal_rank_fusion([sparse, dense], weights=[0.0, 1.0])

    assert sparse_first[0].chunk_index == 0
    assert dense_first[0].chunk_index == 1


def test_rrf_k_controls_how_much_top_rank_matters() -> None:
    """k 越小，靠前名次的优势越大（原论文取 60，越大越平缓）。"""
    one = [ScoredChunk(0, 1.0), ScoredChunk(1, 0.9)]
    two = [ScoredChunk(1, 1.0), ScoredChunk(2, 0.9)]

    small_k = reciprocal_rank_fusion([one, two], k=1)
    large_k = reciprocal_rank_fusion([one, two], k=1000)

    # 文档 1 在 small_k 下靠两路第二名累积的分数应该更接近第一名
    assert small_k[0].chunk_index == 1
    gap_small = small_k[0].score - small_k[1].score
    gap_large = large_k[0].score - large_k[1].score
    assert gap_small > gap_large


def test_rrf_rejects_invalid_arguments() -> None:
    ranking = [ScoredChunk(0, 1.0)]

    with pytest.raises(ValueError):
        reciprocal_rank_fusion([ranking], k=0)
    with pytest.raises(ValueError):
        reciprocal_rank_fusion([ranking], weights=[1.0, 2.0])
    with pytest.raises(ValueError):
        reciprocal_rank_fusion([ranking], weights=[-1.0])


def test_rrf_handles_empty_input() -> None:
    assert reciprocal_rank_fusion([]) == []
    assert reciprocal_rank_fusion([[]]) == []


def test_hybrid_search_single_route_keeps_original_scores() -> None:
    """单路时不融合：否则分数会被改写成 1/(k+rank)，无法与原始分数对照。"""
    sparse_only = hybrid_search("星图", DOCS, enable_dense=False, top_k=2)

    assert sparse_only
    assert all(hit.source == "sparse" for hit in sparse_only)
    # 与直接查 BM25 的结果一致
    direct = Bm25Index().fit(DOCS).search("星图", top_k=2)
    assert [hit.chunk_index for hit in sparse_only] == [hit.chunk_index for hit in direct]


def test_hybrid_search_two_routes_returns_fused_results() -> None:
    dense = [ScoredChunk(4, 0.95, "dense"), ScoredChunk(1, 0.9, "dense")]

    fused = hybrid_search("HNSW Qdrant", DOCS, dense=dense, top_k=3)

    assert fused
    assert all(hit.source == "fused" for hit in fused)
    # 两路都命中的文档应排在只被一路命中的前面
    assert fused[0].chunk_index in {1, 4}


def test_hybrid_search_requires_at_least_one_route() -> None:
    with pytest.raises(ValueError, match="至少要启用一路"):
        hybrid_search("星图", DOCS, enable_sparse=False, enable_dense=False)


def test_hybrid_search_requires_dense_candidates_when_enabled() -> None:
    with pytest.raises(ValueError, match="必须提供 dense 候选"):
        hybrid_search("星图", DOCS, enable_sparse=True, enable_dense=True)


def test_hybrid_search_accepts_prebuilt_index_for_reuse() -> None:
    """索引可以复用：评测台要跑几十组参数，不能每组都重建一次 BM25。"""
    index = Bm25Index().fit(DOCS)

    hits = hybrid_search("向量 嵌入", DOCS, enable_dense=False, bm25_index=index)

    assert hits and hits[0].chunk_index == 1


def test_scored_chunk_rejects_negative_index() -> None:
    with pytest.raises(ValueError):
        ScoredChunk(-1, 1.0)
