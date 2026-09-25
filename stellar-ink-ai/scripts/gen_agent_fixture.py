"""生成只读 Agent 的契约 fixture（两侧单测读同一份）。

与 `gen_style_fixture.py` 同样的理由：**不依赖模型与种子语料**。
这里直接构造一个 `AgentRun`（就像编排层真的跑出来那样），
把「预算触顶但仍带回引用」这一关键形态固化下来 ——
Java 侧据此验证「`doneReason=length` 时 answer 可以为空，但 citations 不能丢」。

用法::

    uv run python scripts/gen_agent_fixture.py
"""

from __future__ import annotations

import json
from pathlib import Path

from app.api.v1.agent import to_result
from app.rag.agent import AgentRun, AgentStep, StopReason
from app.schemas.common import Citation

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console

FIXTURE = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "agent_ask_result.json"
REQUEST_FIXTURE = (
    Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "agent_ask_request.json"
)

#: 构造一次「查了一次、预算触顶」的运行：这是最容易被误读的形态，
#: 也是最需要在契约里钉死的（前端要显示「查到这些但没收敛」而不是空白）
STEPS = [
    AgentStep(index=0, thought="先查站内有没有写过方法", tool="search_posts", label="检索到 2 段"),
    AgentStep(
        index=1, thought="还想再确认一下", error="模型既没有选择工具，也没有给出答案（格式不符）"
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
    return to_result(run).model_dump(by_alias=True, mode="json")


def main() -> None:
    FIXTURE.write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    REQUEST_FIXTURE.write_text(
        json.dumps(
            {"question": "一年写十八万字的方法是什么？", "maxSteps": 4, "maxToolCalls": 6},
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"已写入 {FIXTURE.name} 与 {REQUEST_FIXTURE.name}")


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    main()
