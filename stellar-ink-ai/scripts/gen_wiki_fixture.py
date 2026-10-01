"""生成「带证据的主张抽取」的契约样例（Java 与 Python 两侧共读）。

为什么要脚本生成：手写的样例只证明「我以为长这样」。这里真的跑一遍
`extract_claims`（**走完整的提示词、解析与校验**），只有模型是桩 ——
而且样例里**故意留一条被丢弃的主张**，这样 Java 侧读到的 `stats.dropped`
不是空对象：那个字段才是「是模型不行还是校验挡掉了」的判据。

会随时间变化的字段（`latencyMs`）归一化成 0，其余逐字节确定。

用法：`uv run python scripts/gen_wiki_fixture.py`
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from app.providers.models import ChatResponse, TokenUsage
from app.rag.pipeline import IndexedChunk
from app.rag.wiki import extract_claims

# 与 scripts/ 同目录（`uv run python scripts/x.py` 时它在 sys.path[0]）
from console import use_utf8_console

FIXTURE = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "wiki_claims_result.json"

POST_ID = 7
CHUNK_A = "每天写五百字，一年就是十八万字。写作的关键是把目标切到小得不可能失败。"
CHUNK_B = "深夜写作时，先把手机放到另一个房间，再打开编辑器。"

#: 桩模型：三条**引用为真**、一条**引用是编的**（后者必须被丢弃并计数）；
#: 实体里有两条能站住、一条依附在**被丢弃的主张**上（必须一起被丢掉）
STUB_OUTPUT = {
    "claims": [
        {
            "text": "每天写五百字，一年可以累积十八万字",
            "chunkIndex": 0,
            "quote": "每天写五百字，一年就是十八万字",
            "confidence": 0.9,
        },
        {
            "text": "写作的关键是把目标切到小得不可能失败",
            "chunkIndex": 0,
            "quote": "把目标切到小得不可能失败",
            "confidence": 0.75,
        },
        {
            "text": "作者认为写作必须完全断网",
            "chunkIndex": 1,
            "quote": "必须完全断网",  # ← 原文里没有这句：编的
            "confidence": 0.8,
        },
        {
            "text": "深夜写作要先清掉手机干扰",
            "chunkIndex": 1,
            "quote": "先把手机放到另一个房间",
            "confidence": 0.6,
        },
    ],
    "entities": [
        # 出现在留下来的主张里 → 保留
        {"name": "每天写五百字", "kind": "concept"},
        # 书写差异（空白/全角）应当合并成同一个实体
        {"name": "　每天写五百字 ", "kind": "concept"},
        # 与「每天写五百字」同处一句 → 产生一条**共现关系**
        {"name": "十八万字", "kind": "concept"},
        {"name": "手机干扰", "kind": "concept"},
        # 只出现在**被丢弃**的那条主张里 → 必须一起被丢掉
        {"name": "完全断网", "kind": "concept"},
    ],
}


class _StubChat:
    async def chat(self, messages: list[Any]) -> ChatResponse:
        del messages
        return ChatResponse(
            text=json.dumps(STUB_OUTPUT, ensure_ascii=False),
            finish_reason="stop",
            usage=TokenUsage(prompt_tokens=120, completion_tokens=80, model="fixture-chat"),
        )


def _chunk(index: int, text: str) -> IndexedChunk:
    return IndexedChunk(
        chunk_id=f"p{POST_ID}-c{index}",
        post_id=POST_ID,
        text=f"《一年十八万字》{text}",
        payload={
            "chunkIndex": index,
            "text": text,
            "version": "2026-10-01T00:00:00",
            "contentHash": f"hash{index}",
            "headingPath": "写作方法",
        },
        title="一年十八万字",
    )


def main() -> None:
    result = extract_claims([_chunk(0, CHUNK_A), _chunk(1, CHUNK_B)], _StubChat())
    if result.stats.kept < 2 or not result.stats.dropped:
        # 空样例（或没有丢弃）会让两侧的契约测试都「通过」——那是最坏的结果
        raise SystemExit(
            f"样例不合格：kept={result.stats.kept} dropped={result.stats.dropped}；"
            "它必须同时包含「留下的主张」与「被丢弃的主张」"
        )
    if not result.entities:
        raise SystemExit("样例里一个实体都没留下：契约测试就守不住实体那部分字段")
    if "entityNotInText" not in result.stats.dropped:
        raise SystemExit(
            "样例里没有「依附在被丢弃主张上的实体」："
            "那正是实体校验的关键路径（实体必须站在留下来的主张上）"
        )
    if not result.relations:
        raise SystemExit(
            "样例里没有共现关系：契约测试就守不住「边也带证据」那部分字段"
            "（把两个能站住的实体放进同一句主张即可）"
        )

    payload = {
        "claims": [claim.to_dict() for claim in result.claims],
        "entities": [cluster.to_dict() for cluster in result.entities],
        "relations": [relation.to_dict() for relation in result.relations],
        "stats": result.stats.to_dict(),
        "notes": list(result.notes),
        "usageModel": result.usage_model,
        # 时间字段归一化：否则 fixture 每次生成都不同
        "latencyMs": 0,
    }
    FIXTURE.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"已写入 {FIXTURE.name}：主张 {result.stats.kept} 条、实体 {result.stats.entities} 个，"
        f"丢弃 {result.stats.dropped}"
    )


if __name__ == "__main__":
    use_utf8_console()
    main()
