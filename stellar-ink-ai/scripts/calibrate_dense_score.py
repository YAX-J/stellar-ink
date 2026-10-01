"""按**真实分数分布**标定 `minDenseScore`（Dense 通路的拒答下限）。

为什么必须按真实分布：Dense 通路的拒答只能靠这个余弦绝对下限（`min_score_ratio` 永远不让
结果为空）。曾经拍过一个「看起来合理」的 0.2，结果把向量通路静默清空 —— 因为离线伪向量的
余弦在 0.03 量级，而真实嵌入模型的分布完全是另一回事。见 `docs/ai/status.md` §6 第 4 条。

做法：用**有答案 / 无答案**两组题各跑一遍 dense-only 检索（不设下限），拿到每题的最高余弦，
再扫一遍阈值网格，回答两个问题：
- 有答案题里，多少题**仍然答得对**（top1 ≥ 阈值 且期望文章在 top-k 内）；
- 无答案题里，多少题**被挡住**（top1 < 阈值 → 拒答）。

⚠️ 它需要真实嵌入（今天的免费档是**每模型每日 50 次**，用尽时会如实报错而不是给出零表）。

用法：
    uv run python scripts/calibrate_dense_score.py
    uv run python scripts/calibrate_dense_score.py --top-k 5 --dataset golden_v1.json
"""

from __future__ import annotations

import argparse
import asyncio
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.providers.errors import ProviderError
from app.rag.corpus import build_corpus
from app.rag.eval_runner import EvalDataset
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline

# 与 scripts/ 同目录，供 `uv run python scripts/x.py` 直接 import
from console import use_utf8_console
from scripts.seed_posts import load_seed_posts

DEFAULT_DATASET = "golden_v1.json"

#: 数据集目录。与评测台**同一个**（`tests/fixtures/eval/` —— 黄金集是随代码走的测试资产，
#: 不是运行期数据；放在 fixtures 下两侧的评测用例才读得到同一份）
DATASETS_DIR = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "eval"


@dataclass(frozen=True, slots=True)
class ScoreRow:
    """一道题在 dense-only（无下限）下的原始观测。"""

    case_id: str
    answerable: bool
    top1: float
    hit_at_k: bool

    def to_dict(self) -> dict[str, Any]:
        return {
            "caseId": self.case_id,
            "answerable": self.answerable,
            "top1": round(self.top1, 4),
            "hitAtK": self.hit_at_k,
        }


@dataclass(frozen=True, slots=True)
class ThresholdReport:
    """某个候选阈值下的取舍。"""

    threshold: float
    answerable_kept: float
    unanswerable_refused: float

    @property
    def score(self) -> float:
        """两率之和：既想答得对、也想挡住无答案题。"""
        return self.answerable_kept + self.unanswerable_refused


def sweep(rows: list[ScoreRow], grid: list[float]) -> list[ThresholdReport]:
    """按阈值网格统计两个比例。

    - `answerable_kept`：有答案题里「top1 ≥ 阈值 且期望文章确实命中」的比例。
      注意两个条件都要：只满足分数门槛但没命中，等于答错了还硬答。
    - `unanswerable_refused`：无答案题里 top1 < 阈值的比例。
    """
    answerable = [row for row in rows if row.answerable]
    unanswerable = [row for row in rows if not row.answerable]
    if not answerable or not unanswerable:
        # 单组数据算出来的「两率之和」没有意义：要么全答、要么全拒都能拿满分
        return []

    reports: list[ThresholdReport] = []
    for threshold in grid:
        kept = sum(1 for row in answerable if row.top1 >= threshold and row.hit_at_k)
        refused = sum(1 for row in unanswerable if row.top1 < threshold)
        reports.append(
            ThresholdReport(
                threshold=threshold,
                answerable_kept=kept / len(answerable),
                unanswerable_refused=refused / len(unanswerable),
            )
        )
    return reports


def recommend(reports: list[ThresholdReport]) -> ThresholdReport | None:
    """推荐点 = 两率之和最大；并列时取**拒答率更高**的那个（宁可少答，不要乱答）。

    并列才偏向拒答：反过来的话，一个「答得多但答错」的阈值会赢，
    而这类错误在产品里最难被发现 —— 用户看到的是一个像模像样的答案。
    """
    if not reports:
        return None
    return max(reports, key=lambda item: (round(item.score, 6), item.unanswerable_refused))


def format_report(
    rows: list[ScoreRow], reports: list[ThresholdReport], best: ThresholdReport
) -> str:
    answerable = [row for row in rows if row.answerable]
    unanswerable = [row for row in rows if not row.answerable]
    lines = [
        f"观测：有答案 {len(answerable)} 题、无答案 {len(unanswerable)} 题",
        f"  有答案题的最高余弦：min={min(r.top1 for r in answerable):.4f} "
        f"max={max(r.top1 for r in answerable):.4f}",
        f"  无答案题的最高余弦：min={min(r.top1 for r in unanswerable):.4f} "
        f"max={max(r.top1 for r in unanswerable):.4f}",
        "",
        "阈值        仍答对    挡住无答案",
        "----------------------------------",
    ]
    for report in reports:
        mark = "  ← 推荐" if report is best else ""
        lines.append(
            f"{report.threshold:>7.3f}   {report.answerable_kept:>6.2%}   "
            f"{report.unanswerable_refused:>8.2%}{mark}"
        )
    lines += [
        "",
        f"建议 minDenseScore = {best.threshold:.3f}"
        f"（仍答对 {best.answerable_kept:.0%}，挡住无答案 {best.unanswerable_refused:.0%}）",
        "填到三处：app/api/v1/qa.py 的 QA_RETRIEVAL、app/api/v1/agent.py 的 AGENT_RETRIEVAL、",
        "以及评测台里 dense / hybrid / hybrid+rerank 三组的 minDenseScore。",
        "⚠️ 换嵌入模型后必须重新标定（分布随模型变，包括维度）。",
    ]
    return "\n".join(lines)


async def collect_rows(dataset: EvalDataset, *, top_k: int) -> list[ScoreRow]:
    """用**真实**嵌入跑一遍 dense-only 检索，收集每题的最高余弦。

    走 `app/providers/runtime.registry()`（配置的唯一解析器）而不是自己读库/读环境：
    离线脚本与在线端点必须用**同一份配置解析结果**，否则标出来的门限对应的是另一个模型。
    """
    from app.providers.runtime import registry

    model = registry()
    pipeline = RetrievalPipeline(
        corpus=build_corpus(load_seed_posts()),
        # 只用 dense、且**不设下限**：要的就是原始分布
        config=RetrievalConfig(
            enable_sparse=False,
            enable_dense=True,
            enable_rerank=False,
            min_dense_score=0.0,
            label="dense-calibration",
        ),
        embedder=model.embedding_model(),
    )

    rows: list[ScoreRow] = []
    for case in dataset.cases:
        outcome = await pipeline.retrieve(case.question, top_k=top_k)
        top1 = max((hit.score for hit in outcome.hits), default=0.0)
        rows.append(
            ScoreRow(
                case_id=case.case_id,
                answerable=case.answerable,
                top1=top1,
                hit_at_k=bool(set(outcome.posts) & set(case.expected_post_ids)),
            )
        )
    await model.aclose()
    return rows


def _grid(rows: list[ScoreRow]) -> list[float]:
    """阈值网格 = 观测到的每个 top1（数据驱动，不拍脑袋定步长）。"""
    values = {round(row.top1, 4) for row in rows}
    values.add(0.0)
    return sorted(values)


def main() -> int:
    parser = argparse.ArgumentParser(description="按真实分数分布标定 minDenseScore")
    parser.add_argument("--dataset", default=DEFAULT_DATASET, help="data/eval 下的黄金集文件名")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--json", action="store_true", help="额外输出机器可读的 JSON")
    args = parser.parse_args()

    dataset = EvalDataset.load(DATASETS_DIR / args.dataset)
    print(
        f"数据集 {dataset.name}："
        f"{dataset.answerable_count} 有答案 / {dataset.unanswerable_count} 无答案"
    )
    try:
        rows = asyncio.run(collect_rows(dataset, top_k=args.top_k))
    except ProviderError as error:
        # **不产出零表**：上游不可用时给出一张「全是 0」的分数分布，会被当成真实结论拿去定阈值
        print(f"[×] 跑不了：{error}")
        print("    标定需要真实嵌入；额度/限流问题解决后再跑（不要用离线伪向量凑一个门限出来）。")
        return 1

    reports = sweep(rows, _grid(rows))
    best = recommend(reports)
    if best is None:
        print("[×] 数据集里缺少「有答案」或「无答案」其中一组，无法标定。")
        print("    只有一组时任何阈值都能拿满分：全答或全拒都行 —— 那不是在标定。")
        return 1
    print(format_report(rows, reports, best))
    if args.json:
        print(json.dumps({"rows": [row.to_dict() for row in rows]}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    use_utf8_console()
    raise SystemExit(main())
