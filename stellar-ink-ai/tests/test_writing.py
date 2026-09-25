"""Copilot 编排测试：候选解析、草稿只进本次请求、不按格式回答要报错。

这一层最容易出的问题是**把失败伪装成正常**：模型胡言乱语时返回空候选，
前端显示「暂无建议」，作者以为是「这段写得已经很好」。所以测试重点在
「解析不出来必须报错」，以及「草稿没有被拿去别处」。
"""

from __future__ import annotations

import pytest

from app.providers.models import ChatResponse, TokenUsage
from app.rag.writing import (
    WritingCopilot,
    WritingSettings,
    _truncate,
    parse_candidates,
)
from app.schemas.writing import WritingSuggestRequest, WritingTask, WritingTone


class _Chat:
    """可控对话桩：记录收到的消息、按脚本返回。"""

    def __init__(self, text: str) -> None:
        self.text = text
        self.calls: list[list[object]] = []

    async def chat(self, messages, *, temperature=None, max_tokens=None) -> ChatResponse:
        self.calls.append(list(messages))
        return ChatResponse(
            text=self.text,
            usage=TokenUsage.of(100, 40, latency_ms=12, model="stub-chat"),
        )


def _draft() -> str:
    return "今晚星星很多。我坐在窗边，屋里很安静，我写了很久的字。"


def _request(task=WritingTask.POLISH, **kwargs) -> WritingSuggestRequest:
    payload = {"task": task, "draft": _draft(), "candidate_count": 2}
    payload.update(kwargs)
    return WritingSuggestRequest(**payload)


# ---------------------------- 候选解析 ----------------------------


def test_parses_json_candidates() -> None:
    raw = (
        '[{"text": "今晚有星。", "rationale": "去掉程度词"},'
        ' {"text": "屋里有静。", "rationale": ""}]'
    )

    candidates = parse_candidates(raw, limit=3)

    assert [candidate.text for candidate in candidates] == ["今晚有星。", "屋里有静。"]
    assert candidates[0].rationale == "去掉程度词"
    assert candidates[1].rationale is None, "空理由要落成 None，不要留空字符串"


def test_parses_json_wrapped_in_prose() -> None:
    """模型常在数组前后加一句解释：能挑出数组就照样解析。"""
    raw = '好的，给你两个版本：\n[{"text": "版本一"}, {"text": "版本二"}]\n希望有帮助。'

    candidates = parse_candidates(raw, limit=3)

    assert [candidate.text for candidate in candidates] == ["版本一", "版本二"]


def test_parses_plain_text_blocks_as_fallback() -> None:
    raw = "理由：更克制\n今晚有星，我在窗边写字。\n---\n屋里有静，字写得慢。"

    candidates = parse_candidates(raw, limit=3)

    assert len(candidates) == 2
    assert candidates[0].text == "今晚有星，我在窗边写字。"
    assert candidates[0].rationale == "更克制"
    assert candidates[1].rationale is None


def test_parses_bare_strings_in_json() -> None:
    candidates = parse_candidates('["候选一", "候选二"]', limit=2)

    assert [candidate.text for candidate in candidates] == ["候选一", "候选二"]


def test_limit_is_respected() -> None:
    raw = '["一", "二", "三", "四"]'

    assert len(parse_candidates(raw, limit=2)) == 2


def test_refuses_to_invent_candidates() -> None:
    """格式不符要报错：静默返回空列表会让作者以为「没什么可改」。"""
    with pytest.raises(ValueError, match="解析不出任何候选"):
        parse_candidates("抱歉，我无法完成这个请求。", limit=2)


def test_single_value_tasks_accept_plain_text() -> None:
    """标题/标签/摘要本来就只要一个值：单段纯文本是合理回答，不该被拒。"""
    candidates = parse_candidates("夜里的字", limit=1, expect_single=True)

    assert [candidate.text for candidate in candidates] == ["夜里的字"]


def test_blank_output_is_an_error_not_an_empty_result() -> None:
    with pytest.raises(ValueError, match="返回为空"):
        parse_candidates("   ", limit=2)


def test_limit_must_be_positive() -> None:
    with pytest.raises(ValueError, match="limit"):
        parse_candidates("[]", limit=0)


# ---------------------------- 编排 ----------------------------


async def test_suggest_returns_candidates_with_usage() -> None:
    chat = _Chat('[{"text": "今晚有星。", "rationale": "去掉程度词"}]')
    copilot = WritingCopilot(chat=chat)

    result = await copilot.suggest(_request())

    assert result.task is WritingTask.POLISH
    assert len(result.candidates) == 1
    assert result.usage.model == "stub-chat"
    assert result.usage.total_tokens == 140
    assert copilot.chat_calls == 1


async def test_prompt_carries_task_tone_and_draft() -> None:
    chat = _Chat('["改好了"]')
    copilot = WritingCopilot(chat=chat)

    await copilot.suggest(_request(tone=WritingTone.RESTRAINED))

    system, user = chat.calls[0]
    assert system.role == "system"
    assert "只输出候选本身" in system.content, "系统提示要约束输出形态"
    assert "润色" in user.content, "任务指令要进提示词"
    assert "更克制" in user.content, "风格目标要进提示词"
    assert "今晚星星很多" in user.content, "草稿要进提示词"
    assert "JSON" in user.content, "要明确要求结构化输出，解析才稳"


async def test_instruction_overrides_tone() -> None:
    chat = _Chat('["改好了"]')
    copilot = WritingCopilot(chat=chat)

    await copilot.suggest(_request(tone=WritingTone.COLLOQUIAL, instruction="改得像电报"))

    _, user = chat.calls[0]
    assert "改得像电报" in user.content
    assert "更口语" not in user.content, "给了附加要求就以它为准"


async def test_title_task_needs_no_draft() -> None:
    """拟标题有草稿更好，但契约允许空草稿：不能因此报错。"""
    chat = _Chat('["夜里的字"]')
    copilot = WritingCopilot(chat=chat)

    result = await copilot.suggest(WritingSuggestRequest(task=WritingTask.TITLE, draft="夜里的字"))

    assert result.candidates[0].text == "夜里的字"


async def test_content_task_without_draft_is_rejected_by_contract() -> None:
    with pytest.raises(ValueError, match="需要非空 draft"):
        WritingSuggestRequest(task=WritingTask.POLISH, draft="   ")


async def test_suggest_propagates_parse_failure() -> None:
    copilot = WritingCopilot(chat=_Chat("我想了想，还是算了。"))

    with pytest.raises(ValueError, match="解析不出任何候选"):
        await copilot.suggest(_request())


def test_settings_are_validated() -> None:
    with pytest.raises(ValueError, match="max_draft_chars"):
        WritingSettings(max_draft_chars=10)


# ---------------------------- 草稿截断 ----------------------------


def test_short_draft_is_untouched() -> None:
    assert _truncate("短草稿", 100) == "短草稿"


def test_long_draft_keeps_head_and_tail() -> None:
    """开头定调子、结尾是续写接点：中间省掉最划算，且要标明省略了多少字。"""
    draft = "开" * 100 + "中" * 800 + "尾" * 100

    truncated = _truncate(draft, 200)

    assert truncated.startswith("开")
    assert truncated.endswith("尾")
    assert "中间省略" in truncated
    assert len(truncated) < len(draft)
