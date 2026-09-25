"""本地基线评测：切块 → BM25 → 指标 → 对比表，全程不需要 Qdrant 与模型密钥。

用途：
1. **证明 C 层真的能跑**：黄金集 30 题跑完，输出 Recall@K / MRR / 拒答率的真实数字；
2. **作为基线对照**：等 Qdrant 接上后，Dense / 混合 / 混合+重排 都要与这份纯 BM25 基线比，
   否则没法回答「高级链路到底有没有用」。

用法：``uv run python scripts/eval_local_baseline.py [min_score]``

关于 `MIN_SCORE`（绝对下限）：它是唯一能让检索返回空、从而触发拒答的机制。
实测结论（复现见 `scripts/score_distribution.py`，语料 29 篇文章 / 41 个子块）：
**有答案题里分数最低的约 14.3，无答案题里分数最高的约 24.1 —— 两者重叠**，
所以单靠 BM25 分数无法可靠拒答。

完整权衡曲线见 `scripts/calibrate_min_score.py`，几个关键档（同一份黄金集）：

| min_score | 正确拒答 | 误拒 | recall@1 | recall@3 |
|---|---|---|---|---|
| 3.0（几乎不拦） | 0/10 | 0 | 0.833 | 0.942 |
| **11.0（默认）** | **4/10** | **0** | **0.833** | **0.942** |
| 13.0 | 6/10 | 0 | 0.833 | 0.917 |
| 16.0（开始伤人） | 8/10 | 2 | 0.817 | 0.858 |

默认取 11.0 的理由是「在不掉召回的前提下让闸门真的落下来」——它由本语料实测得来，
**换语料（文章更多、更长）必须重标**，因为 BM25 原始分随语料变化，不是 0-1 的相似度。
想真正把两个分布分开，要靠主题相关性判定或 Dense 相似度下限，那要等接上向量库。
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

from app.rag.eval_runner import EvalDataset, StrategySpec, run_dataset
from app.rag.metrics import DEFAULT_KS
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline, build_corpus

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console
from scripts.seed_posts import load_seed_posts

GOLDEN = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "eval" / "golden_v1.json"

#: 绝对下限：唯一能让检索返回空、从而触发拒答的机制。
#: 11.0 由本语料实测得来（`calibrate_min_score.py`）：能拒掉 4 道确实无答案的题，
#: 且一道有答案题都不误拒、召回不掉。这是「让闸门真的落下来」的取值，不是最漂亮的取值 ——
#: 两个分数分布本身重叠，换语料必须重标。
DEFAULT_MIN_SCORE = 11.0

#: 相对门限：过滤明显弱于最高分的候选，主要作用是提精度
MIN_SCORE_RATIO = 0.5


async def main() -> None:
    min_score = float(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_MIN_SCORE
    posts = load_seed_posts()
    corpus = build_corpus(posts)
    dataset = EvalDataset.load(GOLDEN)
    config = RetrievalConfig(
        enable_sparse=True,
        enable_dense=False,
        min_score=min_score,
        min_score_ratio=MIN_SCORE_RATIO,
        label="bm25_baseline",
    )
    # 用的是正式检索管道（只是只开 Sparse 一路）：基线与被对比的高级链路是同一段编排，
    # 否则「Dense/混合比基线好多少」这个结论会因为两套实现而失真。
    pipeline = RetrievalPipeline(corpus=corpus, config=config)

    result = await run_dataset(
        dataset,
        [
            StrategySpec(
                "bm25_baseline",
                pipeline,
                top_k=10,
                description=f"纯 BM25（无向量、无重排，min_score={min_score}）",
            )
        ],
        ks=DEFAULT_KS,
    )

    print(f"语料：{len(posts)} 篇文章 → {len(corpus)} 个子块")
    print(
        f"数据集：{dataset.name}（{dataset.summary()['cases']} 题，"
        f"无答案 {dataset.unanswerable_count} 题）\n"
    )

    metrics = result.per_strategy["bm25_baseline"]
    for name in (
        "recall@1",
        "recall@3",
        "recall@5",
        "recall@10",
        "precision@5",
        "ndcg@5",
        "mrr",
        "refusalRate",
        "falseRefusalRate",
        "citationAccuracy",
    ):
        print(f"  {name:<20} {metrics.get(name)}")

    missed = [
        case.case_id
        for case in result.cases
        if case.case_type == "answerable"
        and not (set(case.retrieved_posts) & set(case.relevant_posts))
    ]
    partial = [
        f"{case.case_id} 漏 {sorted(set(case.relevant_posts) - set(case.retrieved_posts))}"
        for case in result.cases
        if case.case_type == "answerable"
        and set(case.retrieved_posts) & set(case.relevant_posts)
        and set(case.relevant_posts) - set(case.retrieved_posts)
    ]
    print(f"\n未命中任何相关文章的有答案题：{missed or '无'}")
    print(f"部分漏召（Top-10 里少了标注文章）：{partial or '无'}")

    refused = [case.case_id for case in result.cases if case.refused]
    print(f"实际拒答的题（单题异常也记为拒答）：{refused or '无'}")
    print("\n提示：这是纯 BM25 基线，用于对照；接上 Qdrant 后请与 Dense/混合/重排对比。")


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    asyncio.run(main())
