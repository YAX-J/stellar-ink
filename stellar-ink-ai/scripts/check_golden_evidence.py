"""标注自洽性报告：逐题检查「期望答案里的短语能否在标注文章里找到」。

比测试更宽松也更啰嗦：打印每一题的证据命中情况，便于人工复核黄金集。
判据实现放在这里，`tests/test_golden_set.py` 直接复用 —— 一份规则、两处使用，
免得「测试算的」和「脚本报的」慢慢分叉。
用法：``uv run python scripts/check_golden_evidence.py``
"""

from __future__ import annotations

import re
from pathlib import Path

from app.rag.chunking import PostDocument, chunk_document
from app.rag.eval_runner import EvalCase, EvalDataset

# 控制台编码助手：本文件会被**两种方式**加载 —— 直接运行（scripts/ 在 sys.path[0]）
# 与 pytest 的 `from scripts.x import y`。这里用包内相对导入，两种方式都成立。
# （写成 `from console import ...` 在 pytest 那条路径下会 ModuleNotFoundError，
#   这一点由 tests/test_scripts.py 盯着。）
from scripts.console import use_utf8_console
from scripts.seed_posts import SeedPost, load_seed_posts

GOLDEN = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "eval" / "golden_v1.json"

_SPLIT = re.compile(r"[，。；：、 ]+")


def evidence_anchors(answer: str, *, min_length: int = 4) -> list[str]:
    """把参考答案切成短语锚点：整句逐字比对会因为跨句而大量漏判。"""
    return [
        fragment.strip() for fragment in _SPLIT.split(answer) if len(fragment.strip()) >= min_length
    ]


def labeled_evidence_hits(case: EvalCase, posts: dict[int, SeedPost]) -> tuple[int, int]:
    """返回（能在其中找到证据的标注文章篇数，标注文章总数）。

    只在该文章内部切块，避免把别处的内容当成它的证据。
    """
    anchors = evidence_anchors(case.expected_answer or "")
    expected = list(case.expected_post_ids)
    if not anchors:
        return len(expected), len(expected)
    hits = 0
    for post_id in expected:
        seed = posts.get(post_id)
        if seed is None:
            continue
        document = PostDocument(post_id=seed.post_id, title=seed.title, content=seed.plain)
        if any(
            any(anchor in chunk.text for anchor in anchors) for chunk in chunk_document(document)
        ):
            hits += 1
    return hits, len(expected)


def main() -> None:
    posts = {post.post_id: post for post in load_seed_posts()}
    dataset = EvalDataset.load(GOLDEN)
    weak: list[str] = []
    checked = 0

    for case in dataset.cases:
        if not case.answerable or not case.expected_answer:
            continue
        checked += 1
        hits, total = labeled_evidence_hits(case, posts)
        anchor_count = len(evidence_anchors(case.expected_answer))
        flag = "OK " if hits * 2 >= total else "弱 "
        print(f"{flag}{case.case_id}: {hits}/{total} 篇标注文章里找到证据，锚点 {anchor_count} 个")
        if hits * 2 < total:
            weak.append(case.case_id)

    print(f"\n共检查 {checked} 道有答案题；证据不足的：{weak or '无'}")


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    main()


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    main()
