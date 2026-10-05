"""多轮历史进提示词的口径（S3）。

守的边界与 M9 记忆那条一模一样，但更容易被写反：

* 摘录是**证据**：要编号、要能点回原文；
* 历史是**语境的参考**：那是**模型自己上一轮说过的话**，同样没有出处可核对。

把历史当证据的表现很具体：模型拿自己上一轮的答案当出处往下推 ——
而那段旧答案本来也可能引用错了，于是错误会在多轮里被反复「证实」。
所以提示词必须明确：不得当事实陈述、不得编号引用、与摘录冲突时以摘录为准。

另一半是**上限**：历史是参考不是内容，堆多了只会挤掉真正要引用的摘录。
"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.rag.qa import HISTORY_PROMPT, MEMORY_PROMPT, SYSTEM_PROMPT, _user_prompt
from app.schemas.qa import MAX_HISTORY_TURNS, HistoryTurn, QaStreamRequest


def _excerpt() -> object:
    """一个最小的摘录对象（只用得到 number / chunk.title / snippet）。"""

    class _Chunk:
        title = "夜里的写法"

    class _Excerpt:
        number = 1
        chunk = _Chunk()
        snippet = "我习惯把句子写短。"

    return _Excerpt()


def _turn(
    question: str = "上次那个报错是怎么修的？",
    answer: str = "加 `-Dfile.encoding=UTF-8`。",
) -> HistoryTurn:
    return HistoryTurn(question=question, answer=answer)


def test_prompt_without_history_has_no_history_section() -> None:
    prompt = _user_prompt("怎么写短句", [_excerpt()])

    assert "前面的问答" not in prompt
    assert "站内摘录：" in prompt


def test_history_comes_before_excerpts_and_is_marked_as_context() -> None:
    prompt = _user_prompt("那它呢？", [_excerpt()], (), [_turn()])

    assert HISTORY_PROMPT in prompt
    assert "- 问：上次那个报错是怎么修的？" in prompt
    assert "加 `-Dfile.encoding=UTF-8`。" in prompt
    # 历史段在摘录**之前**：先说明哪些是语境，再给证据
    assert prompt.index("前面的问答") < prompt.index("站内摘录：")
    # 追问本身仍然是最后那句「问题：」
    assert prompt.rstrip().endswith("并用 [编号] 标注来源。")


def test_history_and_memory_keep_their_own_warnings() -> None:
    """两段同时存在时各自带说明：混成一句会让「哪部分不是证据」说不清。"""
    prompt = _user_prompt("那它呢？", [_excerpt()], ["作者偏好短句"], [_turn()])

    assert MEMORY_PROMPT in prompt
    assert HISTORY_PROMPT in prompt
    assert prompt.index("长期记忆") < prompt.index("前面的问答")


def test_history_warning_forbids_citation() -> None:
    assert "不是站内内容" in HISTORY_PROMPT
    assert "不是证据" in HISTORY_PROMPT
    assert "不要" in HISTORY_PROMPT and "编号或引用" in HISTORY_PROMPT


def test_system_prompt_still_demands_evidence_after_history() -> None:
    """加了历史之后，系统提示词的「只依据摘录」不能被削弱。"""
    assert "只依据" in SYSTEM_PROMPT
    assert "编造" in SYSTEM_PROMPT


def test_request_accepts_history_and_defaults_to_empty() -> None:
    assert QaStreamRequest(question="问一句").history == [], "单轮问答不带历史"
    request = QaStreamRequest(question="那它呢？", history=[_turn()])

    assert len(request.history) == 1
    assert request.history[0].question == "上次那个报错是怎么修的？"


def test_history_answers_are_bounded() -> None:
    """单轮历史的答案有长度上限：无界输入会把上下文撑爆。"""
    with pytest.raises(ValidationError):
        HistoryTurn(question="问", answer="答" * 2001)
    with pytest.raises(ValidationError):
        HistoryTurn(question="", answer="答")


def test_too_many_turns_are_rejected() -> None:
    """最多 6 轮：历史是参考不是内容，堆多了只会挤掉摘录。"""
    with pytest.raises(ValidationError):
        QaStreamRequest(
            question="那它呢？",
            history=[_turn(f"第 {index} 问", "答") for index in range(MAX_HISTORY_TURNS + 1)],
        )
