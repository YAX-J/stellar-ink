"""星笺问答的 SSE 事件契约（`POST /qa/stream`）。

为什么单独一层：**事件顺序本身就是协议**。前端要在「模型还没吐完」时就渲染引用与拒答，
Java 侧要把帧原样转成 `ResponseBodyEmitter`，两侧都得知道「谁先谁后、字段叫什么」。
把它写在这里，两侧的单测读同一批 fixture（`tests/fixtures/qa_stream_events.json`）。

事件顺序（固定）：

1. `meta`     —— 先到，让前端立刻知道「请求已受理、模型是谁」，流式体验从这一帧开始
2. `citation` —— 每条一个事件。**先于 delta 发出**：引用来自检索，模型还在生成时它就已经确定了
3. `delta`    —— 增量正文，可能 0..n 个
4. `done`     —— 收尾：`doneReason` + `usage` + `evidenceSufficient`

`error` 是旁路事件：一旦发出就不会再有 `done`（前端据此区分「说完了」与「断了」）。

线格式刻意**不用命名事件**（`event: delta`），而是把类型放进 `data` 的 JSON 里：
NDJSON-over-SSE 用同一套解析就能处理，Java 侧不必再认一套事件名表，
前端 `fetch` 流式读取时也不用处理 `addEventListener` 的类型白名单。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

from app.schemas.common import AiErrorCode, Citation, DoneReason, Usage

#: 事件类型常量：两侧（Python / Java / 前端）都按这几个字面量走
EVENT_META = "meta"
EVENT_CITATION = "citation"
EVENT_DELTA = "delta"
EVENT_DONE = "done"
EVENT_ERROR = "error"

EVENT_TYPES = (EVENT_META, EVENT_CITATION, EVENT_DELTA, EVENT_DONE, EVENT_ERROR)

#: SSE 帧分隔符与心跳注释（代理常把长时间无数据的连接掐掉）
FRAME_TERMINATOR = "\n\n"
COMMENT_PREFIX = ": "


@dataclass(frozen=True, slots=True)
class StreamEvent:
    """一个待发送的事件。

    `payload` 的键名一律驼峰（与 Java DTO、前端 JS 一致），因为它是**跨语言**的部分；
    Python 内部的蛇形命名到这一层就结束了。
    """

    type: str
    payload: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.type not in EVENT_TYPES:
            raise ValueError(f"未知事件类型：{self.type}")

    def to_frame(self) -> str:
        """编码成 SSE 帧：`data: {json}\\n\\n`。"""
        body = json.dumps({"type": self.type, **self.payload}, ensure_ascii=False)
        return f"data: {body}{FRAME_TERMINATOR}"


def meta_event(*, model: str | None, question_length: int, top_k: int) -> StreamEvent:
    """受理确认。

    刻意**不回显问题原文**：它已经在前端手里，回显只会让日志/抓包里多一份用户输入。
    """
    return StreamEvent(
        EVENT_META,
        {"model": model, "questionLength": question_length, "topK": top_k},
    )


def citation_event(citation: Citation) -> StreamEvent:
    return StreamEvent(EVENT_CITATION, {"citation": _dump(citation)})


def delta_event(text: str) -> StreamEvent:
    return StreamEvent(EVENT_DELTA, {"text": text})


def done_event(
    *,
    answer: str,
    done_reason: DoneReason,
    usage: Usage,
    evidence_sufficient: bool,
) -> StreamEvent:
    """收尾事件：字段与非流式 `QaAnswer` 对齐，前端两条路径可以共用渲染逻辑。"""
    return StreamEvent(
        EVENT_DONE,
        {
            "answer": answer,
            "doneReason": done_reason.value,
            "usage": _dump(usage),
            "evidenceSufficient": evidence_sufficient,
        },
    )


def error_event(code: AiErrorCode, message: str) -> StreamEvent:
    """可展示的错误：只给结论，不带栈与上游报文。"""
    return StreamEvent(EVENT_ERROR, {"code": code.value, "message": message})


def heartbeat() -> str:
    """SSE 注释行：不触发任何事件，只为了让中间的代理知道连接还活着。"""
    return f"{COMMENT_PREFIX}ping{FRAME_TERMINATOR}"


def _dump(model: Any) -> dict[str, Any]:
    """按契约别名（驼峰）导出。

    `model_dump` 的静态返回类型是 `dict[str, Any]`，但 mypy 只把它当 Any ——
    这里显式收一次口，避免调用方拿到 Any 之后一路传染下去。
    """
    payload: dict[str, Any] = model.model_dump(by_alias=True, mode="json")
    return payload
