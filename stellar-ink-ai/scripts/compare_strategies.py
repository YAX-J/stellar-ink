"""策略对比：同一份黄金集，跑「单路 Sparse / 单路 Dense / 混合 / 混合+重排」四组配置。

两种模型来源，**同一份 `CONFIGS`、同一条 `RetrievalPipeline`**，只有「模型从哪来」不同：

- `--provider fake`（默认）：FakeProvider 的哈希伪向量。离线可跑、不需要密钥，
  存在意义是**证明开关是真的** —— 若某两组结果完全相同，说明那个开关没接上
  （这比指标高低更值得关注）。但伪向量没有语义，`dense` 一列接近随机，
  **不代表真实质量**，也不该拿来比较检索策略的优劣。
- `--provider panel`：走应用自己的那份配置（`app.api.v1.assembly` → `runtime.registry()`），
  即面板里配的真实模型。这是「高级链路到底有没有用」的答案来源，
  也是唯一能把 `minDenseScore`（Dense 通路的拒答绝对下限）标定出来的口径 ——
  门限必须按**真实分数分布**定，离线伪向量的分数（0.03 量级）定出来的门限会静默清空向量通路。

⚠️ 不另写一套配置：两边的策略集合、指标列都来自本文件顶部的常量，
否则「命令行跑出来的数字」与「面板跑出来的数字」会从配置这一步就开始分叉。

用法::

    uv run python scripts/compare_strategies.py                     # 离线（Fake，默认）
    uv run python scripts/compare_strategies.py --provider panel    # 真实模型（读面板配置）
"""

from __future__ import annotations

import argparse
import asyncio
from pathlib import Path

from app.providers import runtime
from app.providers.config_source import ProviderConfigError, describe_sources
from app.providers.errors import ProviderError
from app.providers.fake import FakeProvider
from app.rag import corpus as corpus_module
from app.rag.eval_runner import EvalDataset, StrategySpec, run_dataset
from app.rag.metrics import DEFAULT_KS
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console

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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="黄金集上的检索策略对比")
    parser.add_argument(
        "--provider",
        choices=("fake", "panel"),
        default="fake",
        help="fake=离线伪向量（默认）；panel=面板里配的真实模型",
    )
    return parser.parse_args()


def build_strategies(provider: str) -> list[StrategySpec]:
    """按来源装配策略集合。

    `panel` 分支刻意**复用 `assembly.pipeline_for`** 而不是自己 `runtime.registry()`：
    那条路带着「按语料版本 + 开关 + 配置指纹缓存管道」的语义，
    自己装配会让「整库嵌入」在一次运行里发生好几遍 —— 那是费用，不是慢一点。
    """
    corpus = corpus_module.cached_corpus()
    if provider == "panel":
        # 延迟导入：离线跑（默认）不该因为这条路径去读库
        from app.api.v1 import assembly  # noqa: PLC0415 - 见上

        roles = sorted(
            {role for config in CONFIGS for role in assembly.roles_for(config, chat=False)}
        )
        # 预检：缺角色时一次说清缺哪些、去哪儿填，而不是跑到第三组策略才报错
        runtime.require_roles(*roles)
        return [
            StrategySpec(
                config.label,
                assembly.pipeline_for(config),
                top_k=10,
                description=str(config.describe()),
            )
            for config in CONFIGS
        ]

    fake = FakeProvider()
    return [
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


async def main() -> int:
    args = parse_args()
    dataset = EvalDataset.load(GOLDEN)
    posts = corpus_module.cached_posts()
    corpus = corpus_module.cached_corpus()

    if args.provider == "panel":
        print(f"模型来源：面板配置（真实模型）｜{describe_sources()}")
    else:
        print("模型来源：FakeProvider（离线哈希伪向量，无语义）")

    try:
        strategies = build_strategies(args.provider)
    except (ProviderError, ProviderConfigError) as error:
        # 装配失败要给出可读原因与「配置是从哪读的」，而不是一个栈
        print(f"✗ 装配失败：{error}")
        print(f"  配置来源：{describe_sources()}")
        return 1

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

    # 上游故障必须单独喊出来：被降级成「拒答」的题会让那一行看起来像「这个策略全错」。
    # 实测踩过：免费嵌入模型在第 4 组触发 429，`hybrid+rerank` 整行 recall 0 / 拒答率 1.0，
    # 而它跟重排质量一点关系都没有。
    errored = {
        label: metrics.get("errorCount")
        for label, metrics in result.per_strategy.items()
        if metrics.get("errorCount")
    }
    if errored:
        print(
            f"\n[×] 有题目因上游失败（限流/超时/5xx）被记为「拒答」，这些行不可用：{errored}"
            "\n    真因见服务端日志的「评测单题失败」；与检索或重排质量无关，请稍后重跑。"
        )

    if args.provider == "panel":
        print(
            "\n[i] 这是**真实模型**上的数字：Dense 两列现在有语义，可以拿它回答"
            "\n    「混合与重排到底有没有用」。下一步是按这份分数分布标定 minDenseScore"
            "\n    （`scripts/calibrate_min_score.py`），Dense 通路的拒答只能靠绝对下限。"
        )
    else:
        if any({"dense", "hybrid+rerank"} == set(pair) for pair in same):
            print(
                "  注：dense 与 hybrid+rerank 相同是 Fake 的必然结果 ——"
                " 假重排用的就是这个伪向量函数，它不带来新信息；"
                "真重排必须换一个模型（bge-reranker 之类）。"
            )
        print(
            "\n[!] Dense 用 FakeProvider 的哈希伪向量（无语义），所以 dense 两列接近随机 ——"
            "\n   这恰好证明向量通路真的在起作用（没有偷偷退回 Sparse）。"
            "\n   真实质量用 `--provider panel` 重跑，别拿这两列下结论。"
            "\n   另外：不加相对门限时 precision@5 偏低，是 post 级去重后前 5 名混进了弱候选，"
            "\n   对照 `sparse+floor` 一行可见门限对精度的影响（召回几乎不掉）。"
        )
    return 0


def _ranking(metrics: dict[str, object]) -> dict[str, object]:
    """只取排序/拒答指标：延迟必然不同，拿它比对会让「两组结果一样」永远测不出来。"""
    return {column: metrics.get(column) for column in COLUMNS}


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    raise SystemExit(asyncio.run(main()))
