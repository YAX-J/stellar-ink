"""增量索引对账（M4）：算出「哪些文章要重建、哪些要删除、哪些不用动」。

    uv run python scripts/index_reconcile.py               # 对账并打印计划（不写库）
    uv run python scripts/index_reconcile.py --apply       # 对账后真的重建（要 Qdrant 可达）

⚠️ 这条路径需要 Qdrant 可达（本机经 SSH 隧道；见 `deploy/docker/.env.example` 末节）。
拿不到索引哈希时会**如实报错**，而不是假设「都没变」—— 那会让改动永远进不了索引。

为什么不做「文章保存时发事件」：那要求 content-service 与 ai-service 之间有同步调用
（本项目目前刻意没有），而且事件丢了就永久不一致。对账每次都能自己发现不一致，
代价是读一遍索引的**哈希**（不读向量）。这就是 roadmap 说的「先证明索引正确，再上增量」。
"""

from __future__ import annotations

import argparse
import asyncio

from app.rag import corpus as corpus_module
from app.rag.index_reconcile import reconcile
from app.rag.qdrant_store import QdrantConfig, QdrantVectorStore

# 控制台编码助手与本文件同目录
from console import use_utf8_console


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="增量索引对账：不变 / 要重建 / 要删除")
    parser.add_argument("--apply", action="store_true", help="对账后真的重建（默认只打印计划）")
    parser.add_argument("--collection", default="", help="集合名（默认用配置里的）")
    return parser.parse_args()


async def main() -> int:
    args = parse_args()
    config = QdrantConfig(collection=args.collection) if args.collection else QdrantConfig()
    store = QdrantVectorStore(config)
    try:
        try:
            health = await store.health()
        except Exception as error:  # noqa: BLE001 - 连不上要给人话，不是栈
            print(f"✗ 连不上 Qdrant（{config.base_url}）：{error}")
            print("  本机需要先开隧道；命令见 deploy/docker/.env.example 末节。")
            return 1
        print(f"Qdrant：{config.base_url} 集合 {config.collection}｜{health}")

        indexed = await store.hashes_by_post()
        chunks = corpus_module.cached_corpus()
        plan = reconcile(chunks, indexed)
    finally:
        await store.aclose()

    print(f"\n语料 {len(chunks)} 个子块；索引里有 {len(indexed)} 篇文章的哈希")

    def preview(label: str, ids: list[int]) -> str:
        shown = f"{ids[:10]}{' …' if len(ids) > 10 else ''}"
        return f"  {label}{len(ids)}：{shown}"

    print(preview("不变（跳过）", plan.unchanged))
    print(preview("要重建     ", plan.changed))
    print(preview("要删除     ", plan.removed))
    if plan.unverifiable:
        print(
            f"  ⚠️ 其中 {len(plan.unverifiable)} 篇在索引里读不到哈希"
            f"（按要重建处理）：{plan.unverifiable[:10]}"
        )
    for note in plan.notes:
        print(f"  [i] {note}")

    if not args.apply:
        print("\n（只对账，没动索引。要真的重建请加 --apply）")
        return 0
    if not plan.changed and not plan.removed:
        print("\n索引与语料一致，无需重建。")
        return 0
    print(
        "\n[!] --apply 需要重建管道（嵌入 + 写库）；请用 POST /admin/index/rebuild 走既有任务链路："
    )
    print(f"    postIds={plan.changed}")
    print(f"    removed（重建时由 delete_by_post_ids 一并清理）：{plan.removed}")
    print(
        "    刻意不在这里直接重建：那条链路有任务状态与调用账（scene=index），"
        "自己写一遍会绕过它们。"
    )
    return 0


if __name__ == "__main__":
    use_utf8_console()
    raise SystemExit(asyncio.run(main()))
