"""检索的纯算法层：分词、BM25（Sparse）、RRF 融合、Dense 余弦。

为什么自己写而不是引框架：这四件事是「换成别的检索策略就要改」的核心逻辑，
必须能逐层测试与替换（roadmap §2.2 的取舍）；而且它们都是纯函数，
不依赖 Qdrant / 模型，因此可以先用固定用例把排序行为钉死。

中文分词的做法（刻意不用 jieba 等分词器）：
- CJK 按**单字**入索引 + **双字 bigram** 一起入。单字保证召回（不漏词），
  bigram 提供一点词序信息（「星图」与「图星」不再等价）。
- 拉丁文与数字按词切分并转小写（`BM25` 与 `bm25` 应命中同一批文档）。
- 为什么不引分词器：多一个词典依赖、多一份不可复现（词典版本会影响排序），
  而中文技术文章里专有名词（bge-m3、Qdrant、RAG）大多是拉丁串，规则切分够用；
  真需要时它只影响 `tokenize` 一处，Dense 通路不受影响。
"""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass, field

#: BM25 默认参数：k1 控制词频饱和，b 控制长度归一化
DEFAULT_K1 = 1.5
DEFAULT_B = 0.75

#: RRF 默认 k：原论文取 60，越大越弱化「排名极靠前」的优势
DEFAULT_RRF_K = 60

#: CJK 区段（含中日韩统一表意文字与常见扩展）
_CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
#: 拉丁字母/数字串（含 `.` `-` `_` `+` `#`，让 bge-m3 / gpt-4 / c++ 保持完整）
_LATIN_TOKEN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+#-]*")

STOPWORDS: frozenset[str] = frozenset(
    {
        "the",
        "a",
        "an",
        "of",
        "to",
        "in",
        "is",
        "are",
        "and",
        "or",
        "for",
        "on",
        "with",
        "是",
        "的",
        "了",
        "和",
        "与",
        "在",
        "有",
        "就",
        "都",
        "也",
        "而",
        "把",
        "被",
        "对",
    }
)


def tokenize(text: str) -> list[str]:
    """把文本切成检索用的 token（CJK 单字 + bigram，拉丁整词，全部小写）。

    同一段文本永远得到同一串 token，因此 BM25 的排序是完全可复现的 ——
    这是评测台能对比「不同切块/不同参数」的前提。
    """
    if not text:
        return []

    tokens: list[str] = []
    cjk_run: list[str] = []

    def flush_cjk() -> None:
        if not cjk_run:
            return
        tokens.extend(cjk_run)
        # bigram：为中文补一点词序信息；单字仍保留，避免漏召回
        tokens.extend(cjk_run[index] + cjk_run[index + 1] for index in range(len(cjk_run) - 1))
        cjk_run.clear()

    index = 0
    while index < len(text):
        char = text[index]
        if _CJK.match(char):
            cjk_run.append(char)
            index += 1
            continue
        flush_cjk()
        match = _LATIN_TOKEN.match(text, index)
        if match:
            tokens.append(match.group().lower())
            index = match.end()
            continue
        index += 1
    flush_cjk()

    return [token for token in tokens if token not in STOPWORDS]


@dataclass(frozen=True, slots=True)
class ScoredChunk:
    """一个候选：`chunk_index` 指向调用方传入的文档下标。"""

    chunk_index: int
    score: float
    source: str = "sparse"

    def __post_init__(self) -> None:
        if self.chunk_index < 0:
            raise ValueError("chunk_index 不能为负")


@dataclass(slots=True)
class Bm25Index:
    """极简 BM25（Okapi）实现，支持增量式重建。

    用法：`index = Bm25Index(); index.fit(documents); index.search(query, top_k=5)`。
    文档以字符串列表传入（调用方传切块后的文本），索引内部只保存统计量，
    不持有原文，避免在内存里复制一份语料。
    """

    k1: float = DEFAULT_K1
    b: float = DEFAULT_B
    _doc_tokens: list[list[str]] = field(default_factory=list, init=False)
    _doc_len: list[int] = field(default_factory=list, init=False)
    _term_freqs: list[Counter[str]] = field(default_factory=list, init=False)
    _doc_freq: Counter[str] = field(default_factory=Counter, init=False)
    _avg_len: float = field(default=0.0, init=False)

    def __post_init__(self) -> None:
        if self.k1 <= 0:
            raise ValueError("k1 必须为正：否则词频饱和项会失效")
        if not 0 <= self.b <= 1:
            raise ValueError("b 必须在 [0, 1]：它表示长度归一化的强度")

    def fit(self, documents: list[str]) -> Bm25Index:
        """重建索引。重复调用等价于「换一批文档」，不会累加旧统计。"""
        self._doc_tokens = [tokenize(document) for document in documents]
        self._doc_len = [len(tokens) for tokens in self._doc_tokens]
        self._term_freqs = [Counter(tokens) for tokens in self._doc_tokens]
        self._doc_freq = Counter()
        for tokens in self._doc_tokens:
            for term in set(tokens):
                self._doc_freq[term] += 1
        total = sum(self._doc_len)
        self._avg_len = (total / len(self._doc_len)) if self._doc_len else 0.0
        return self

    @property
    def size(self) -> int:
        return len(self._doc_tokens)

    def search(self, query: str, *, top_k: int = 10) -> list[ScoredChunk]:
        """返回按 BM25 分数降序的候选；分数为 0 的文档不返回。"""
        if top_k <= 0:
            raise ValueError("top_k 必须为正")
        if not self._doc_tokens:
            return []

        query_terms = tokenize(query)
        if not query_terms:
            return []

        total_docs = len(self._doc_tokens)
        scores: list[tuple[int, float]] = []
        for doc_index, freqs in enumerate(self._term_freqs):
            score = 0.0
            length = self._doc_len[doc_index]
            norm = 1 - self.b + self.b * (length / self._avg_len) if self._avg_len else 1.0
            for term in query_terms:
                frequency = freqs.get(term, 0)
                if frequency == 0:
                    continue
                doc_freq = self._doc_freq.get(term, 0)
                # 加 0.5 平滑：避免 df == N 时 idf 变成负数（词在所有文档里都出现）
                idf = math.log(1 + (total_docs - doc_freq + 0.5) / (doc_freq + 0.5))
                score += idf * (frequency * (self.k1 + 1)) / (frequency + self.k1 * norm)
            if score > 0:
                scores.append((doc_index, score))

        # 同分时按下标升序：保证结果稳定可复现（否则评测台每次跑出来的顺序都可能不同）
        scores.sort(key=lambda item: (-item[1], item[0]))
        return [
            ScoredChunk(chunk_index=index, score=round(score, 6), source="sparse")
            for index, score in scores[:top_k]
        ]


def dense_scores(
    query_vector: list[float],
    doc_vectors: list[list[float]],
    *,
    top_k: int = 10,
) -> list[ScoredChunk]:
    """Dense 通路：余弦相似度（本地实现，不依赖向量库）。

    用途有两个：真实 Qdrant 不可用时的降级通路；以及评测台里「同一批向量、
    只换融合策略」的对照实验。向量归一化由调用方负责或在此兜底。
    """
    if top_k <= 0:
        raise ValueError("top_k 必须为正")
    if not doc_vectors:
        return []

    query_norm = math.sqrt(sum(value * value for value in query_vector)) or 1.0
    scored: list[tuple[int, float]] = []
    for index, vector in enumerate(doc_vectors):
        if len(vector) != len(query_vector):
            # 维度不一致说明索引与查询用的不是同一个嵌入模型，必须报错而不是跳过
            raise ValueError(
                f"向量维度不一致：query={len(query_vector)}, doc[{index}]={len(vector)}"
            )
        dot = sum(a * b for a, b in zip(query_vector, vector, strict=True))
        norm = (math.sqrt(sum(value * value for value in vector)) or 1.0) * query_norm
        scored.append((index, dot / norm))

    scored.sort(key=lambda item: (-item[1], item[0]))
    return [
        ScoredChunk(chunk_index=index, score=round(score, 6), source="dense")
        for index, score in scored[:top_k]
    ]


def reciprocal_rank_fusion(
    rankings: list[list[ScoredChunk]],
    *,
    k: int = DEFAULT_RRF_K,
    weights: list[float] | None = None,
    top_k: int | None = None,
) -> list[ScoredChunk]:
    """RRF 融合多路排名：`score = Σ weight_i / (k + rank_i)`。

    为什么用排名而不是分数直接相加：BM25 与余弦相似度的量纲完全不同，
    直接加权求和需要针对每个模型调归一化；RRF 只用名次，天然免疫量纲差异，
    这也是它成为混合检索默认做法的原因。

    每一路内部必须已按相关性降序（调用方保证），同分由各路自身稳定排序决定。
    """
    if k <= 0:
        raise ValueError("k 必须为正：它决定靠前名次的衰减速度")
    if not rankings:
        return []
    if weights is not None and len(weights) != len(rankings):
        raise ValueError("weights 数量必须与 rankings 一致")
    if any(weight < 0 for weight in (weights or [])):
        raise ValueError("weights 不能为负：负权重会把相关结果往下压")

    resolved_weights = weights if weights is not None else [1.0] * len(rankings)
    fused: dict[int, float] = {}
    best_rank: dict[int, int] = {}
    for path_index, ranking in enumerate(rankings):
        weight = resolved_weights[path_index]
        for rank, candidate in enumerate(ranking):
            contribution = weight / (k + rank + 1)
            fused[candidate.chunk_index] = fused.get(candidate.chunk_index, 0.0) + contribution
            # 记录最好名次，用于同分时稳定排序（更靠前者优先）
            best_rank[candidate.chunk_index] = min(best_rank.get(candidate.chunk_index, rank), rank)

    ordered = sorted(fused.items(), key=lambda item: (-item[1], best_rank[item[0]], item[0]))
    limit = top_k if top_k is not None else len(ordered)
    return [
        ScoredChunk(chunk_index=index, score=round(score, 6), source="fused")
        for index, score in ordered[:limit]
    ]


def hybrid_search(
    query: str,
    documents: list[str],
    *,
    dense: list[ScoredChunk] | None = None,
    top_k: int = 10,
    candidate_k: int = 50,
    bm25_weight: float = 1.0,
    dense_weight: float = 1.0,
    enable_sparse: bool = True,
    enable_dense: bool = True,
    rrf_k: int = DEFAULT_RRF_K,
    bm25_index: Bm25Index | None = None,
) -> list[ScoredChunk]:
    """混合检索：按开关组合 Sparse / Dense，再用 RRF 融合。

    这个函数的参数就是「前端实验室」要暴露的那几个开关 ——
    单路 vs 多路、各路权重、候选数，都能在这里组合出来，
    因此评测台可以直接遍历参数组合做对比。
    """
    if not enable_sparse and not enable_dense:
        raise ValueError("至少要启用一路检索，否则没有候选可融合")

    rankings: list[list[ScoredChunk]] = []
    weights: list[float] = []

    if enable_sparse:
        index = bm25_index if bm25_index is not None else Bm25Index().fit(documents)
        sparse_hits = index.search(query, top_k=candidate_k)
        rankings.append(sparse_hits)
        weights.append(bm25_weight)

    if enable_dense:
        if dense is None:
            raise ValueError("启用 dense 通路时必须提供 dense 候选（本层不自己调用嵌入模型）")
        rankings.append(dense[:candidate_k])
        weights.append(dense_weight)

    if len(rankings) == 1:
        # 单路时不做融合：融合会把分数改写成 1/(k+rank)，不利于与原分数对照
        return rankings[0][:top_k]
    return reciprocal_rank_fusion(rankings, k=rrf_k, weights=weights, top_k=top_k)
