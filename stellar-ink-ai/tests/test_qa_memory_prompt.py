"""记忆进提示词的口径（M9-3）。

这一层守的是一条**很容易被写反**的边界：长期记忆与文章摘录是两种东西。

* 摘录是**证据**：要编号、要能点回原文；
* 记忆是**语气与取舍**的参考：来自一次对话，**没有出处可核对**。

把两者混在一起的表现很具体：模型会把「作者喜欢短句」写成
「文章里说他喜欢短句」——而读者点开那篇文章，里面根本没有这句话。
所以提示词里必须明确：不得当事实陈述、不得编号引用、与摘录冲突时以摘录为准。
"""

from __future__ import annotations

import pytest

from app.rag.qa import MEMORY_PROMPT, SYSTEM_PROMPT, _user_prompt


def _excerpt() -> object:
    """一个最小的摘录对象（只用得到 number / chunk.title / snippet）。"""

    class _Chunk:
        title = "夜里的写法"

    class _Excerpt:
        number = 1
        chunk = _Chunk()
        snippet = "我习惯把句子写短。"

    return _Excerpt()


def test_prompt_without_memories_has_no_memory_section() -> None:
    prompt = _user_prompt("怎么写短句", [_excerpt()])

    assert "长期记忆" not in prompt
    assert "文章摘录：" in prompt
    assert "[1]《夜里的写法》" in prompt


def test_memories_come_with_their_own_warning() -> None:
    prompt = _user_prompt("怎么写短句", [_excerpt()], ["作者偏好短句", "作者不再写第二季"])

    assert MEMORY_PROMPT in prompt
    assert "- 作者偏好短句" in prompt
    assert "- 作者不再写第二季" in prompt
    # 记忆段在摘录**之前**：先定语气，再给证据
    assert prompt.index("长期记忆") < prompt.index("文章摘录：")
    # 与摘录冲突时以摘录为准 —— 这条不能少，否则记忆会盖过语料
    assert "以摘录为准" in MEMORY_PROMPT


def test_memory_warning_forbids_citation() -> None:
    assert "不是文章内容" in MEMORY_PROMPT
    assert "不要" in MEMORY_PROMPT and "编号引用" in MEMORY_PROMPT


def test_system_prompt_still_demands_evidence() -> None:
    """加了记忆之后，系统提示词的「只依据摘录」不能被削弱。"""
    assert "只依据" in SYSTEM_PROMPT
    assert "编造" in SYSTEM_PROMPT


@pytest.mark.parametrize("memories", [[], ["作者偏好短句"]])
def test_prompt_always_ends_with_the_citation_instruction(memories: list[str]) -> None:
    prompt = _user_prompt("怎么写短句", [_excerpt()], memories)

    assert prompt.rstrip().endswith("并用 [编号] 标注来源。")
