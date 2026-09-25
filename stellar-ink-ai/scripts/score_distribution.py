"""分布对照：把「有答案题的相关分」与「无答案题的最高分」并排打出来。

用途：判断「能不能单靠检索分数决定拒答」。如果能，两个分布应当有间隙；
如果重叠，就说明必须引入别的判据（查询与语料的主题相关性、Dense 相似度下限、或让模型判定），
而不是继续拧检索阈值 —— 那份结论要写进汇报，避免以后反复试错。
"""

from __future__ import annotations

from pathlib import Path

from app.rag.chunking import PostDocument, chunk_document
from app.rag.eval_runner import EvalDataset
from app.rag.retrieval import Bm25Index

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console
from scripts.seed_posts import load_seed_posts

GOLDEN = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "eval" / "golden_v1.json"


def main() -> None:
    posts = load_seed_posts()
    dataset = EvalDataset.load(GOLDEN)
    texts: list[str] = []
    chunk_post: list[int] = []
    for post in posts:
        document = PostDocument(post_id=post.post_id, title=post.title, content=post.plain)
        for chunk in chunk_document(document):
            if chunk.chunk_type == "child":
                texts.append(f"{post.title}\n{chunk.text}")
                chunk_post.append(post.post_id)
    index = Bm25Index().fit(texts)

    relevant_scores: list[tuple[str, float]] = []
    unanswerable_scores: list[tuple[str, float]] = []
    for case in dataset.cases:
        hits = index.search(case.question, top_k=10)
        relevant_exact = [
            hit.score for hit in hits if chunk_post[hit.chunk_index] in set(case.expected_post_ids)
        ]
        top = hits[0].score if hits else 0.0
        if case.answerable:
            relevant_scores.append((case.case_id, round(max(relevant_exact, default=0.0), 3)))
        else:
            unanswerable_scores.append((case.case_id, round(top, 3)))

    relevant_scores.sort(key=lambda item: item[1])
    unanswerable_scores.sort(key=lambda item: item[1], reverse=True)

    print("有答案题：正确文章的分数（升序，前 10）")
    for case_id, score in relevant_scores[:10]:
        print(f"  {case_id}: {score}")
    print("\n无答案题：最高分（降序，前 10）")
    for case_id, score in unanswerable_scores[:10]:
        print(f"  {case_id}: {score}")

    low = relevant_scores[0][1]
    high = unanswerable_scores[0][1]
    print(f"\n最低相关分 = {low}，最高无关分 = {high}")
    print(
        "→ 有间隙，可用绝对下限拒答"
        if high < low
        else "→ 重叠：单靠 BM25 分数无法可靠拒答（需要主题相关性判定或 Dense 下限）"
    )


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    main()
