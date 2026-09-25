"""策略对比：同一份黄金集，跑「单路 Sparse / 单路 Dense / 混合 / 混合+重排」四组配置。

用途有两个：
1. **证明开关是真的**：四组配置走的是同一条 `RetrievalPipeline`，只有开关不同；
   如果某两组结果完全一样，说明那个开关没接上（这比指标高低更值得关注）。
2. **给前端对比表定形状**：`compare_strategies` 的输出就是 `/ai-lab` 评测页签要渲染的
   结构（`{策略: 指标}`），这个脚本先在命令行把它跑出来。

⚠️ 关于 Dense 两列的读数：这里用 `FakeProvider` 的**哈希伪向量**，它没有语义，
所以 Dense 的表现接近随机，**不代表真实 bge-m3 的质量**。这两列现在的意义是
「向量通路接对了没有」，真实质量必须等 Qdrant + 真模型接上后重跑。

用法：``uv run python scripts/compare_strategies.py``
"""

from __future__ import annotations

import asyncio
from pathlib import Path

from app.providers.fake import FakeProvider
from app.rag.eval_runner import EvalDataset, StrategySpec, run_dataset
from app.rag.metrics import DEFAULT_KS
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline, build_corpus

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console
from scripts.seed_posts import load_seed_posts

GOLDEN = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "eval" / "golden_v1.json"

#: 五组配置：前四组只差开关；最后一组给 Sparse 加上本地基线标定过的门限，用来看门限的影响
CONFIGS = (
    RetrievalConfig(enable_sparse=True, enable_dense=False, label="sparse"),
    RetrievalConfig(enable_sparse=False, enable_dense=True, label="dense"),
    RetrievalConfig(enable_sparse=True, enable_dense=True, label="hybrid"),
    RetrievalConfig(
        enable_sparse=True, enable_dense=True, enable_rerank=True, label="hybrid+rerank"
    ),
    RetrievalConfig(
        enable_sparse=True,
        enable_dense=False,
        min_score=11.0,
        min_score_ratio=0.5,
        label="sparse+floor",
    ),
)

#: 对比表里要看的列（顺序即前端表格的列顺序）
COLUMNS = ("recall@1", "recall@3", "recall@5", "precision@5", "ndcg@5", "mrr", "refusalRate")


async def main() -> None:
    posts = load_seed_posts()
    corpus = build_corpus(posts)
    dataset = EvalDataset.load(GOLDEN)
    fake = FakeProvider()

    strategies = [
        StrategySpec(
            config.label,
            RetrievalPipeline(
                corpus=corpus,
                config=config,
                embedder=fake if config.enable_dense else None,
                reranker=fake if config.enable_rerank else None,
            ),
            top_k=10,
            description=str(config.describe()),
        )
        for config in CONFIGS
    ]

    result = await run_dataset(dataset, strategies, ks=DEFAULT_KS)

    print(f"语料 {len(posts)} 篇文章 → {len(corpus)} 个子块")
    print(
        f"数据集 {dataset.name}：{dataset.answerable_count} 有答案 / "
        f"{dataset.unanswerable_count} 无答案\n"
    )

    width = max(len(column) for column in COLUMNS) + 2
    header = "策略".ljust(16) + "".join(column.rjust(width) for column in COLUMNS)
    print(header)
    print("-" * len(header))
    for config in CONFIGS:
        metrics = result.per_strategy[config.label]
        cells = "".join(str(metrics.get(column)).rjust(width) for column in COLUMNS)
        print(config.label.ljust(16) + cells)

    same = sorted(
        (a.label, b.label)
        for index, a in enumerate(CONFIGS)
        for b in CONFIGS[index + 1 :]
        if _ranking(result.per_strategy[a.label]) == _ranking(result.per_strategy[b.label])
    )
    print(f"\n排序指标完全相同的配置对：{same or '无（说明每个开关都真的改变了排序）'}")
    if any({"dense", "hybrid+rerank"} == set(pair) for pair in same):
        print(
            "  注：dense 与 hybrid+rerank 相同是 Fake 的必然结果 —— 假重排用的就是这个伪向量函数，"
            "它不带来新信息；真重排必须换一个模型（bge-reranker 之类）。"
        )
    print(
        "\n[!] Dense 用 FakeProvider 的哈希伪向量（无语义），所以 dense 两列接近随机 ——"
        "\n   这恰好证明向量通路真的在起作用（没有偷偷退回 Sparse）；"
        "\n   真实 Dense / 混合 / 重排的质量必须等 Qdrant + bge-m3 接上后重跑。"
        "\n   另外：不加相对门限时 precision@5 偏低，是 post 级去重后前 5 名混进了弱候选，"
        "\n   对照 `sparse+floor` 一行可见门限对精度的影响（召回几乎不掉）。"
    )


def _ranking(metrics: dict[str, object]) -> dict[str, object]:
    """只取排序/拒答指标：延迟必然不同，拿它比对会让「两组结果一样」永远测不出来。"""
    return {column: metrics.get(column) for column in COLUMNS}


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    asyncio.run(main())
