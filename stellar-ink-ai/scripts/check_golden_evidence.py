"""标注自洽性报告：逐题检查「期望答案里的短语能否在标注文章里找到」。

比测试更宽松也更啰嗦：打印每一题的证据命中情况，便于人工复核黄金集。
判据实现放在这里，`tests/test_golden_set.py` 直接复用 —— 一份规则、两处使用，
免得「测试算的」和「脚本报的」慢慢分叉。

**第二部分是「无答案题」的自查**（2026-10-02 补）：无答案题的定义是「语料里没有答案」，
而标错这一类题的代价是**标定/评测结论整体失真** —— 实测遇到过一组无答案题，
它们的分数比有答案题还高（同主题但语料里确实没答案），直接把 `minDenseScore` 标到「全拒」。
所以这里给出两条**线索**（不是判定，别当结论用）：
1. 这题是不是**写了参考答案文本却没标文章**（标注写了一半）；
2. 提问里的长片段是否**逐字出现在语料**里 —— 出现了说明这题的措辞与某篇文章高度重合，
   值得人工确认「是不是其实有答案」。

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

#: 无答案题自查时，提问里要多长的连续片段才算「与语料重合」。
#: 太短会到处都是（「怎么」这种词必然出现），太长则只有当提问整句照抄文章时才会命中。
QUESTION_SPAN_LENGTH = 8


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


def question_spans(question: str, *, min_length: int = QUESTION_SPAN_LENGTH) -> list[str]:
    """把提问切成用于「与语料重合」判断的长片段。

    按标点与空格切开，再保留够长的那些 —— 这是**线索**而不是判定：
    提问与某篇文章用词接近，不代表答案就在那篇里（也可能只是同一主题）。
    """
    return [
        fragment.strip()
        for fragment in _SPLIT.split(question)
        if len(fragment.strip()) >= min_length
    ]


def corpus_overlap_spans(question: str, corpus_text: str) -> list[str]:
    """提问里**逐字出现在语料**中的长片段（空列表 = 措辞上不重合）。"""
    return [span for span in question_spans(question) if span in corpus_text]


def unanswerable_clues(
    dataset: EvalDataset, posts: dict[int, SeedPost]
) -> list[tuple[str, list[str], bool]]:
    """无答案题的自查线索：`(caseId, 与语料重合的片段, 是否写了参考答案文本)`。

    ⚠️ 为什么不是「是否带了 expectedPosts」：`answerable` 是**派生属性**
    （`expected_post_ids` 非空即为有答案），所以「无答案题带标注文章」在数据模型里表达不出来 ——
    写了标注文章的题**按定义就是有答案题**。真正能出现的矛盾是另一种：
    **没标文章、却写了参考答案文本** —— 那说明标注写了一半（有答案却忘了标文章），
    这种题会被算进拒答率，把结论拉偏。
    """
    corpus_text = "\n".join(seed.plain for seed in posts.values())
    clues: list[tuple[str, list[str], bool]] = []
    for case in dataset.cases:
        if case.answerable:
            continue
        overlaps = corpus_overlap_spans(case.question, corpus_text)
        clues.append((case.case_id, overlaps, bool(case.expected_answer)))
    return clues


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

    # ---- 无答案题自查（线索，不是判定）----
    clues = unanswerable_clues(dataset, posts)
    risky = [case_id for case_id, overlaps, has_answer in clues if overlaps or has_answer]
    print(f"\n无答案题自查（{len(clues)} 道）—— 下面是**供人工复核的线索**，不是判定：")
    for case_id, overlaps, has_answer in clues:
        marks = []
        if has_answer:
            marks.append("⚠️ 写了参考答案文本却没标文章（标注写了一半？）")
        if overlaps:
            marks.append(f"措辞与语料重合：{'、'.join(overlaps[:3])}")
        print(f"  {case_id}: {'；'.join(marks) if marks else '无重合、无参考答案文本'}")
    if risky:
        print(
            f"\n  需要人工确认的：{risky}\n"
            "  判据：**措辞重合不等于有答案**（同主题很常见）。要确认就得自己读那篇文章，\n"
            "  看它是否真的回答了提问；确认「其实有答案」就改题或补进有答案组，\n"
            "  否则保留 —— 但要知道这类题会拉高分数、并让 minDenseScore 更难标定。"
        )


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    main()
