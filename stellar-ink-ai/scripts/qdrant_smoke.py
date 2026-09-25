"""Qdrant 真实冒烟：隧道打通后跑一次，把「按文档说对了话」升级为「对面确实这么答」。

两段：
A. **协议段**（3 个手工向量）：建集合 → 写入 → 检索 → 相似度下限 → 按文章删点 → 删集合；
   向量是正交的单位向量，所以「同向量的点必须排第一」能直接证明向量与 payload 真的存进去了。
B. **端到端段**（真实切块 + FakeProvider 嵌入）：跑一遍 `IndexPipeline` 写库，
   再用 `RetrievalPipeline(dense_store=store)` 检索回来 —— 证明写路径与读路径能对接上
   （命中必须能回到语料里的 chunk，否则管道会报「索引与语料不一致」）。

全程用一个独立命名的临时集合，结束即删，绝不动生产集合。

用法：
    ssh -N -L 6333:127.0.0.1:6333 <server>        # 先开隧道（生产 Qdrant 只绑宿主机回环）
    uv run python scripts/qdrant_smoke.py [base_url]

注意：没有隧道也没有 Qdrant 时，它会明确打印「连不上」而不是假装通过。
"""

from __future__ import annotations

import asyncio
import sys

from app.providers.errors import ProviderError
from app.providers.fake import FakeProvider
from app.rag.index_pipeline import IndexPipeline
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline, build_corpus
from app.rag.qdrant_store import QdrantConfig, QdrantVectorStore, VectorPoint

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console
from scripts.seed_posts import load_seed_posts

TEMP_COLLECTION = "stellar_ink_smoke"
DIMENSION = 8
#: 端到端段只取几篇：冒烟要快，且这一步的意义是「通」而不是「准」
SAMPLE_POSTS = 5


def _point(chunk_id: str, post_id: int, vector: list[float]) -> VectorPoint:
    return VectorPoint(
        chunk_id=chunk_id,
        post_id=post_id,
        vector=vector,
        payload={"text": f"冒烟片段 {chunk_id}", "headingPath": "smoke"},
    )


async def _protocol_stage(store: QdrantVectorStore) -> bool:
    health = await store.health()
    print(f"① 健康检查：{health['title'] or '(无标题)'} v{health['version'] or '?'}")

    info = await store.ensure_collection(dimension=DIMENSION, recreate=True)
    print(f"② 建临时集合 {TEMP_COLLECTION}：维度 {info.dimension}（每次重建，不碰生产集合）")

    written = await store.upsert(
        [
            _point("smoke:p1:c0", 1, [1.0, 0, 0, 0, 0, 0, 0, 0]),
            _point("smoke:p2:c0", 2, [0, 1.0, 0, 0, 0, 0, 0, 0]),
            _point("smoke:p3:c0", 3, [0, 0, 1.0, 0, 0, 0, 0, 0]),
        ]
    )
    print(f"③ 写入 {written} 个点")

    after_write = await store.collection_info()
    print(f"④ 集合当前点数：{after_write.points_count}（应为 3）")

    hits = await store.search([1.0, 0, 0, 0, 0, 0, 0, 0], top_k=3)
    top = hits[0]
    print(
        f"⑤ 检索：首条 {top.chunk_id}（postId={top.post_id}）"
        f"分数 {top.score:.4f}；共 {len(hits)} 条"
    )
    if top.chunk_id != "smoke:p1:c0":
        print(f"✗ 同向量的点没有排第一（首条是 {top.chunk_id}）：向量或 payload 没存对")
        return False

    filtered = await store.search([1.0, 0, 0, 0, 0, 0, 0, 0], top_k=3, score_threshold=0.99)
    print(f"⑥ 相似度下限 0.99 时返回 {len(filtered)} 条（拒答机制是否生效）")

    await store.delete_by_post_ids([1, 3])
    after_delete = await store.collection_info()
    print(f"⑦ 按文章删点后剩余：{after_delete.points_count}（应为 1）")
    return after_delete.points_count == 1


async def _end_to_end_stage(store: QdrantVectorStore) -> bool:
    posts = load_seed_posts()[:SAMPLE_POSTS]
    corpus = build_corpus(posts)
    provider = FakeProvider()
    await store.delete_collection()  # 换维度（协议段是 8 维，FakeProvider 是 64 维）

    report = await IndexPipeline(store=store, embedder=provider, point_factory=store).index(posts)
    print(
        f"⑧ 索引 {report.posts} 篇文章 → {report.chunks} 个子块，写入 {report.written} 条"
        f"（维度 {report.dimension}，{report.batches} 批，{report.latency_ms:.0f}ms）"
    )
    if report.written != report.chunks:
        print("✗ 写入数量与子块数量不一致：有 chunk 没进库")
        return False

    info = await store.collection_info()
    print(f"⑨ 集合点数：{info.points_count}（应等于 {report.chunks}）")

    pipeline = RetrievalPipeline(
        corpus=corpus,
        config=RetrievalConfig(enable_sparse=True, enable_dense=True, label="smoke"),
        embedder=provider,
        dense_store=store,
    )
    outcome = await pipeline.retrieve("我为什么坚持写博客", top_k=3)
    print(
        f"⑩ 端到端检索：命中文章 {outcome.posts}"
        f"，chunk {len(outcome.chunks)} 个，拒答={outcome.refused}"
    )
    if outcome.refused or not outcome.chunks:
        print("✗ 端到端没检索到任何片段：写路径与读路径没对接上")
        return False
    known = {chunk.chunk_id for chunk in corpus}
    unknown = [chunk_id for chunk_id in outcome.chunks if chunk_id not in known]
    if unknown:
        print(f"✗ 检索回来的 chunk 不在语料里：{unknown[:3]}")
        return False
    return True


async def main() -> int:
    base_url = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:6333"
    store = QdrantVectorStore(QdrantConfig(base_url=base_url, collection=TEMP_COLLECTION))
    try:
        ok = await _protocol_stage(store)
        if ok:
            ok = await _end_to_end_stage(store)
        await store.delete_collection()
        print("⑪ 已删除临时集合")
    except ProviderError as exc:
        print(f"✗ Qdrant 不可用：{exc}")
        print("  先确认隧道：ssh -N -L 6333:127.0.0.1:6333 <server>；再跑本脚本。")
        await store.aclose()
        return 1
    await store.aclose()
    if not ok:
        return 1
    print("\n✓ 冒烟通过：协议 + 端到端（索引 → 检索）都与真实 Qdrant 对上了")
    return 0


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    raise SystemExit(asyncio.run(main()))
