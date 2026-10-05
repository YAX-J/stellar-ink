"""生成只读 Agent 的契约 fixture（两侧单测读同一份）。

与 `gen_style_fixture.py` 同样的理由：**不依赖模型与种子语料**。
这里直接构造一个 `AgentRun`（就像编排层真的跑出来那样），
把「预算触顶但仍带回引用」这一关键形态固化下来 ——
Java 侧据此验证「`doneReason=length` 时 answer 可以为空，但 citations 不能丢」。

A1 起请求/结果都多了一个 `agent`（司职）：结果里**显式**回显 `searcher`
（而不是靠 Java DTO 的默认值）—— 这份 fixture 的作用就是让两侧都真的读到这个键，
少了它，「回显司职」这件事就只是代码里的一句注释。

A2 起还生成核验那一对（`agent_verify_request/result.json`）。
⚠️ 它用的是**脚本里内联的一小份语料**，不是 `cached_corpus()`：
后者在开发机上读的是线上投影表（`ai_content_snapshot`），**每台机器都不一样**，
那样子测出来的 fixture 会随「本机库里有什么」变化 —— 那种红绿灯谁也修不了。
内联语料是刻意的：契约要固化的是**判定形状**（verdict / checked / problems），
不是某篇文章此刻的正文。

用法::

    uv run python scripts/gen_agent_fixture.py
"""

from __future__ import annotations

import json
from pathlib import Path

from app.agents import registry, verifier
from app.api.v1.agent import to_result, to_verify_result
from app.rag.agent import AgentRun, AgentStep, StopReason
from app.rag.pipeline import IndexedChunk
from app.schemas.common import Citation

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console

FIXTURE = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "agent_ask_result.json"
REQUEST_FIXTURE = (
    Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "agent_ask_request.json"
)
VERIFY_FIXTURE = (
    Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "agent_verify_result.json"
)
VERIFY_REQUEST_FIXTURE = (
    Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "agent_verify_request.json"
)

#: fixture 里用的司职：必须存在，否则两侧读的是一份指向不存在司职的样例
AGENT_NAME = registry.DEFAULT_AGENT

#: 构造一次「查了一次、预算触顶」的运行：这是最容易被误读的形态，
#: 也是最需要在契约里钉死的（前端要显示「查到这些但没收敛」而不是空白）
STEPS = [
    AgentStep(index=0, thought="先查站内有没有写过方法", tool="search_posts", label="检索到 2 段"),
    AgentStep(
        index=1, thought="还想再确认一下", error="模型既没有选择工具，也不知道答案（格式不符）"
    ),
]
CITATIONS = [
    Citation(
        post_id=1,
        title="写作的复利",
        chunk_index=0,
        snippet="一年写十八万字靠的是每天五百字，不是等灵感。",
        score=14.28,
    ),
    Citation(
        post_id=16,
        title="一次 23 秒的请求",
        chunk_index=1,
        snippet="把超时显式写出来，比默认无限等要安全得多。",
        score=9.5,
    ),
]

#: 核验 fixture 的内联语料：只用来给「片段与原文对不上」提供可回查的原文
VERIFY_CORPUS = (
    "写作的复利\n每天写五百字，一年就是十八万字。",
    "一次 23 秒的请求\n把超时显式写出来，比默认无限等要安全得多。",
)

#: 答案里标了 `[1]`，但第二条引用的片段被改过（原文里找不到）——
#: 一个样例同时覆盖「有编号」与「片段对不上」两种判定
VERIFY_ANSWER = "靠的是每天五百字 [1]，超时要显式写出来 [2]。"
VERIFY_CITATIONS = [
    Citation(
        post_id=1,
        title="写作的复利",
        chunk_index=0,
        snippet="每天写五百字，一年就是十八万字。",
        score=14.28,
    ),
    Citation(
        post_id=16,
        title="一次 23 秒的请求",
        chunk_index=1,
        # 原文是「把超时显式写出来…」，这里写成「把超时显式地写出来」→ 观察不到
        snippet="把超时显式地写出来，比默认无限等要安全得多。",
        score=9.5,
    ),
]


def build() -> dict:
    run = AgentRun(
        answer="",
        citations=CITATIONS,
        stop_reason=StopReason.LENGTH,
        steps=STEPS,
        tool_calls=1,
        interrupted_by="budget",
        usage_model="fake",
        latency_ms=42,
    )
    return to_result(run, AGENT_NAME).model_dump(by_alias=True, mode="json")


def build_verify_request() -> dict:
    return {
        "answer": VERIFY_ANSWER,
        "citations": [
            citation.model_dump(by_alias=True, mode="json") for citation in VERIFY_CITATIONS
        ],
    }


def build_verify() -> dict:
    """核验报告：用内联语料跑一遍真实的判定（不是手写的期望值）。"""
    chunks = [
        IndexedChunk(
            chunk_id=f"fixture-{index}",
            post_id=VERIFY_CITATIONS[index].post_id,
            text=text,
            payload={"text": text.split("\n", 1)[1], "chunkIndex": index},
            title=VERIFY_CITATIONS[index].title,
        )
        for index, text in enumerate(VERIFY_CORPUS)
    ]
    report = verifier.verify(
        VERIFY_ANSWER, VERIFY_CITATIONS, index=verifier.chunk_index_of(chunks)
    )
    return to_verify_result(report).model_dump(by_alias=True, mode="json")


def main() -> None:
    FIXTURE.write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    REQUEST_FIXTURE.write_text(
        json.dumps(
            {
                "question": "一年写十八万字的方法是什么？",
                "agent": AGENT_NAME,
                "maxSteps": 4,
                "maxToolCalls": 6,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    VERIFY_FIXTURE.write_text(
        json.dumps(build_verify(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    VERIFY_REQUEST_FIXTURE.write_text(
        json.dumps(build_verify_request(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"已写入 {FIXTURE.name}、{REQUEST_FIXTURE.name}、"
        f"{VERIFY_FIXTURE.name} 与 {VERIFY_REQUEST_FIXTURE.name}"
    )


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    main()
