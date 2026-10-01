"""`check_golden_evidence.py` 的无答案题自查（2026-10-02 补的那一节）。

为什么要有这一节：无答案题的标注错了，代价是**结论整体失真** ——
实测遇到过一组无答案题，分数比有答案题还高（同主题但语料里确实没答案），
直接把 `minDenseScore` 标成「全拒」。

要守的三条：
1. **措辞重合只是线索**，不是判定：同主题的提问当然会与文章用词接近；
2. **无答案题不该带 `expectedPosts`** —— 带了就说明标注自相矛盾（这是硬判据，不是线索）；
3. 脚本**直接运行时只能跑一遍**（曾经 `__main__` 块写了两遍，报告打印两次，
   看的人会以为有两份数据集）。
"""

from __future__ import annotations

import re
from pathlib import Path

from app.rag.eval_runner import EvalCase, EvalDataset
from scripts.check_golden_evidence import (
    QUESTION_SPAN_LENGTH,
    corpus_overlap_spans,
    question_spans,
    unanswerable_clues,
)
from scripts.seed_posts import load_seed_posts

GOLDEN = Path(__file__).parent / "fixtures" / "eval" / "golden_v1.json"
SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "check_golden_evidence.py"


def test_question_spans_needs_enough_characters() -> None:
    """太短的片段到处都是（「怎么」必然出现），不能拿它当重合线索。"""
    spans = question_spans("怎么写，才能坚持下来")

    assert all(len(span) >= QUESTION_SPAN_LENGTH for span in spans)
    assert "怎么写" not in spans, "3 个字的片段不算线索"


def test_corpus_overlap_finds_verbatim_spans_only() -> None:
    question = "每天写五百字是怎么坚持下来的"
    corpus = "……作者说他每天写五百字是怎么坚持下来的？靠的是把目标切小……"

    assert corpus_overlap_spans(question, corpus), "逐字出现的长片段要能找出来"
    assert corpus_overlap_spans(question, "完全无关的另一篇文章") == []


def test_unanswerable_clues_flags_half_written_labels() -> None:
    """「没标文章、却写了参考答案文本」是**能出现的**矛盾（标注写了一半）。

    ⚠️ 这里纠正了一个容易想当然的口径：`answerable` 是**派生属性**
    （`expected_post_ids` 非空即为有答案），所以「无答案题带标注文章」根本表达不出来 ——
    带标注文章的题按定义就是有答案题。真正会发生的矛盾是反过来的那种。
    """
    dataset = EvalDataset(
        name="tmp",
        description="",
        cases=[
            EvalCase(
                case_id="u1",
                question="怎么修好一台坏掉的咖啡机",
                expected_answer="先检查电源",  # ← 有答案文本却没标文章
            ),
            EvalCase(case_id="u2", question="量子纠缠与咖啡因代谢"),
            EvalCase(case_id="a1", question="每天写多少字", expected_post_ids=[1]),
        ],
    )
    posts = {post.post_id: post for post in load_seed_posts()}

    clues = {
        case_id: (overlaps, has_answer)
        for case_id, overlaps, has_answer in unanswerable_clues(dataset, posts)
    }

    assert set(clues) == {"u1", "u2"}, "有答案题不进这份清单"
    assert clues["u1"][1] is True, "写了参考答案文本 → 要标出来"
    assert clues["u2"] == ([], False), "u2 既没答案文本、措辞也不重合"


def test_unanswerable_clues_on_the_real_golden_set() -> None:
    """真实黄金集：跑得动、且那些无答案题**都不带** expectedPosts（标注自洽）。"""
    dataset = EvalDataset.load(GOLDEN)
    posts = {post.post_id: post for post in load_seed_posts()}

    clues = unanswerable_clues(dataset, posts)

    assert clues, "黄金集里应当有无答案题（拒答率指标靠它们）"
    assert all(not has_posts for _, _, has_posts in clues), "无答案题不该带标注文章"


def test_main_guard_appears_exactly_once() -> None:
    """回归用例：`__main__` 块只能有一个。

    实测踩过：文件末尾写了两遍，直接运行时 `main()` 跑两次、报告打印两遍 ——
    这是个「人读工具」，重复输出会让人以为有两份数据集（而不是以为脚本坏了）。
    """
    source = SCRIPT.read_text(encoding="utf-8")

    assert len(re.findall(r'if __name__ == "__main__":', source)) == 1
