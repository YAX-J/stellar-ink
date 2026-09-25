"""门限标定：扫一遍**绝对下限** `min_score`，看「正确拒答」与「误拒」的权衡。

为什么扫绝对下限而不是相对比例：`min_score_ratio` 只按「最高分 × 比例」过滤，
最高分自己永远过线，所以**它永远不会让结果为空**（见 `app/rag/retrieval.py` 的注释），
拒答率恒为 0。真正能让检索返回空、从而拒答的只有绝对下限 —— 这条弯路由本脚本实测确认，
免得以后有人再去拧相对比例。

用法：``uv run python scripts/calibrate_min_score.py``
"""

from __future__ import annotations

import asyncio
from pathlib import Path

from app.rag.eval_runner import EvalDataset, StrategySpec, run_dataset
from app.rag.metrics import DEFAULT_KS
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline, build_corpus

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console
from scripts.seed_posts import load_seed_posts

GOLDEN = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "eval" / "golden_v1.json"

#: 要扫的绝对下限候选（BM25 原始分，不是 0-1 的相似度）
CANDIDATES = (0.0, 3.0, 5.0, 7.0, 8.0, 9.0, 11.0, 13.0, 14.0, 16.0, 20.0)


async def main() -> None:
    posts = load_seed_posts()
    corpus = build_corpus(posts)
    dataset = EvalDataset.load(GOLDEN)
    print(
        f"语料 {len(posts)} 篇 / 数据集 {dataset.name}"
        f"（有答案 {dataset.answerable_count}，无答案 {dataset.unanswerable_count}）"
    )
    header = (
        f"{'下限':>6} {'正确拒答':>8} {'误拒':>6} "
        f"{'Recall@1':>9} {'Recall@3':>9} {'Precision@5':>11}"
    )
    print(header)

    for value in CANDIDATES:
        pipeline = RetrievalPipeline(
            corpus=corpus,
            config=RetrievalConfig(
                enable_sparse=True, enable_dense=False, min_score=value, label="sweep"
            ),
        )
        result = await run_dataset(
            dataset, [StrategySpec("sweep", pipeline, top_k=10)], ks=DEFAULT_KS
        )
        metrics = result.per_strategy["sweep"]
        correct = sum(
            1 for case in result.cases if case.case_type == "unanswerable" and case.refused
        )
        wrong = sum(1 for case in result.cases if case.case_type == "answerable" and case.refused)
        row = (
            f"{value:>6.1f} {correct:>8} {wrong:>6} "
            f"{metrics['recall@1']:>9.3f} {metrics['recall@3']:>9.3f} "
            f"{metrics['precision@5']:>11.3f}"
        )
        print(row)

    print(
        "\n读法：下限调大 → 正确拒答变多，但误拒（本来能答却拒了）也会变多，召回开始掉。"
        "\n原因是两个分数分布重叠：有答案题里最低分 ≈ 14.3，"
        "\n无答案题里最高分 ≈ 24.1（见 score_distribution.py）。"
        "\n所以取哪一档是场景取舍（宁可少编造 / 宁可少漏掉），不是「调到最好看」；"
        "\n想真正分开两个分布，要靠主题相关性判定或向量相似度下限。"
    )


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    asyncio.run(main())
