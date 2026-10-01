"""trace 事件缓冲（E3-4）：有界、隔离，且**绝不收用户内容**。

最后一条是这个模块存在的意义：链路回放要好用，就很容易顺手把提示词与答案也记下来 ——
那样一来，内存里就多了一份隐私数据，而且没有任何界面会提示「这里存着用户的文章」。
所以违规字段是**抛错**而不是截断：静默丢弃会让人以为「已经记下来了」。
"""

from __future__ import annotations

import pytest

from app.core.trace import (
    MAX_EVENTS,
    MAX_TRACES,
    current_trace_id,
    record_event,
    reset_traces,
    trace_snapshot,
)


@pytest.fixture(autouse=True)
def _clean() -> None:
    reset_traces()


def test_events_go_to_the_current_trace(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.core.trace.current_trace_id", lambda: "t-1")

    record_event("retrieval", posts=3, refused=False)
    record_event("tool", tool="search_posts")

    snapshot = trace_snapshot("t-1")
    assert snapshot is not None
    assert [event["kind"] for event in snapshot["events"]] == ["retrieval", "tool"]
    assert snapshot["events"][0]["posts"] == 3
    assert snapshot["startedAtMs"] is not None


def test_without_a_trace_nothing_is_recorded() -> None:
    """离线脚本与单测没有请求上下文：跳过而不是报错。"""
    assert current_trace_id() is None

    record_event("retrieval", posts=1)

    assert trace_snapshot("t-1") is None


def test_unknown_trace_returns_none() -> None:
    assert trace_snapshot("不存在") is None


def test_traces_are_isolated(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.core.trace.current_trace_id", lambda: "t-a")
    record_event("model", call="chat", promptTokens=10)
    monkeypatch.setattr("app.core.trace.current_trace_id", lambda: "t-b")
    record_event("model", call="embed", inputs=2)

    first = trace_snapshot("t-a")
    second = trace_snapshot("t-b")
    assert first is not None and second is not None
    assert first["events"][0]["call"] == "chat"
    assert second["events"][0]["call"] == "embed"


def test_user_content_fields_are_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    """提示词 / 草稿 / 答案 / 问题一律不许进缓冲 —— 抛错而不是截断。"""
    monkeypatch.setattr("app.core.trace.current_trace_id", lambda: "t-1")

    for forbidden in ("prompt", "draft", "answer", "question", "snippet"):
        with pytest.raises(ValueError) as info:
            record_event("model", **{forbidden: "用户内容"})
        assert forbidden in str(info.value)


def test_long_strings_are_truncated_and_none_dropped(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.core.trace.current_trace_id", lambda: "t-1")

    record_event("tool", tool="x" * 300, error=None)

    event = trace_snapshot("t-1")["events"][0]  # type: ignore[index]
    assert len(event["tool"]) <= 121
    assert event["tool"].endswith("…")
    assert "error" not in event, "空值不该占一个字段（回放里全是 null 反而更难读）"


def test_events_per_trace_are_bounded(monkeypatch: pytest.MonkeyPatch) -> None:
    """越过上限丢**最旧**的：排障看的是「这次到底发生了什么」。"""
    monkeypatch.setattr("app.core.trace.current_trace_id", lambda: "t-1")

    for index in range(MAX_EVENTS + 5):
        record_event("model", seq=index)

    events = trace_snapshot("t-1")["events"]  # type: ignore[index]
    assert len(events) == MAX_EVENTS
    assert events[0]["seq"] == 5, "最旧的应当先被丢掉"
    assert events[-1]["seq"] == MAX_EVENTS + 4


def test_traces_are_bounded(monkeypatch: pytest.MonkeyPatch) -> None:
    """没有上限的环形缓冲在长跑的服务里就是一条内存泄漏。"""
    for index in range(MAX_TRACES + 3):
        monkeypatch.setattr("app.core.trace.current_trace_id", lambda i=index: f"t-{i}")
        record_event("model", seq=index)

    assert trace_snapshot("t-0") is None, "最早的 trace 应当被淘汰"
    assert trace_snapshot("t-2") is None
    assert trace_snapshot(f"t-{MAX_TRACES + 2}") is not None


def test_reset_clears_everything(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.core.trace.current_trace_id", lambda: "t-1")
    record_event("model", call="chat")

    reset_traces()

    assert trace_snapshot("t-1") is None
