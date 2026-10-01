"""带证据的主张抽取（E4-1）：**这一版的核心就是校验，所以校验被逐条测**。

要守住的一句话是验收口径：「Wiki 的事实性文本必须能回到证据」。
模型会给出看起来很专业的引用，而其中一部分是**编的** —— 如果编的也能进库，
Wiki 就退化成「模型印象集」，而且比一篇普通摘要更危险：它长得像有据可查。

因此每条拒绝路径都有一个用例，而且**丢弃要分类计数**：
静默丢弃会让「抽出来很少」看起来像模型不行，实际是校验挡掉了一批编造的引用。
"""

from __future__ import annotations

import asyncio
import json

import pytest

from app.providers.fake import FakeProvider
from app.providers.models import ChatResponse, TokenUsage
from app.rag.pipeline import IndexedChunk
from app.rag.wiki import (
    DROP_DUPLICATE,
    DROP_QUOTE_NOT_FOUND,
    DROP_TEXT_TOO_LONG,
    DROP_TEXT_TOO_SHORT,
    DROP_UNKNOWN_CHUNK,
    extract_claims,
    extract_claims_async,
)

CHUNK_A = "每天写五百字，一年就是十八万字。写作的关键是把目标切到小得不可能失败。"
CHUNK_B = "深夜写作时，先把手机放到另一个房间，再打开编辑器。"


def chunk(post_id: int, index: int, text: str) -> IndexedChunk:
    return IndexedChunk(
        chunk_id=f"p{post_id}-c{index}",
        post_id=post_id,
        text=f"《测试文章》{text}",
        payload={
            "chunkIndex": index,
            "text": text,
            "version": f"v{post_id}",
            "contentHash": f"h{post_id}{index}",
            "headingPath": "写作方法",
        },
        title="测试文章",
    )


class StubChat:
    """按脚本返回 JSON 的假模型（**校验逻辑之外的唯一变量就是它**）。"""

    def __init__(self, payload: object) -> None:
        self.payload = payload
        self.prompts: list[str] = []

    async def chat(self, messages):  # noqa: ANN001, ANN201 - 与 ChatModel 协议一致即可
        self.prompts.append(messages[-1].content)
        text = self.payload if isinstance(self.payload, str) else json.dumps(self.payload)
        return ChatResponse(text=text, finish_reason="stop", usage=TokenUsage(model="stub"))


def two_chunks() -> list[IndexedChunk]:
    return [chunk(7, 0, CHUNK_A), chunk(7, 1, CHUNK_B)]


def test_valid_claim_is_kept_with_its_evidence() -> None:
    chat = StubChat(
        {
            "claims": [
                {
                    "text": "每天写五百字，一年可以累积十八万字",
                    "chunkIndex": 0,
                    "quote": "每天写五百字，一年就是十八万字",
                    "confidence": 0.9,
                },
            ]
        }
    )

    result = extract_claims(two_chunks(), chat)

    assert result.stats.proposed == 1
    assert result.stats.kept == 1
    assert result.stats.dropped == {}
    claim = result.claims[0]
    assert claim.post_id == 7
    assert claim.chunk_index == 0
    assert claim.quote == "每天写五百字，一年就是十八万字"
    # 证据要能定位回**具体版本**的文章与段落（不然文章改了就没法失效重建）
    assert claim.post_version == "v7"
    assert claim.content_hash == "h70"
    assert claim.heading_path == "写作方法"
    assert claim.confidence == 0.9


def test_invented_quote_is_dropped_and_counted() -> None:
    """**编造的引用必须被挡下**：这是整个 E4 的验收口径。"""
    chat = StubChat(
        {
            "claims": [
                {
                    "text": "作者每天写两千字",
                    "chunkIndex": 0,
                    "quote": "每天写两千字",
                    "confidence": 0.9,
                },
            ]
        }
    )

    result = extract_claims(two_chunks(), chat)

    assert result.claims == []
    assert result.stats.dropped == {DROP_QUOTE_NOT_FOUND: 1}
    assert any("引用找不到原文依据" in note for note in result.notes), "要能解释清楚为什么丢了"


def test_quote_from_another_chunk_is_dropped() -> None:
    """引用确实在文章里，但**不在它标注的那一段**里 —— 同样不算数。"""
    chat = StubChat(
        {
            "claims": [
                {
                    "text": "写作前要把手机放到另一个房间",
                    "chunkIndex": 0,
                    "quote": "把手机放到另一个房间",
                    "confidence": 0.8,
                },
            ]
        }
    )

    result = extract_claims(two_chunks(), chat)

    assert result.stats.dropped == {DROP_QUOTE_NOT_FOUND: 1}


def test_whitespace_differences_do_not_break_evidence() -> None:
    """模型常把换行/空格写得和原文不一致：那不是「引用不实」。"""
    chat = StubChat(
        {
            "claims": [
                {
                    "text": "把目标切到小得不可能失败",
                    "chunkIndex": 0,
                    "quote": "每天写五百字，\n一年就是十八万字。",
                    "confidence": 0.7,
                },
            ]
        }
    )

    result = extract_claims(two_chunks(), chat)

    assert result.stats.kept == 1


def test_unknown_chunk_index_is_dropped() -> None:
    chat = StubChat(
        {
            "claims": [
                {"text": "这是一条指向不存在段落的主张", "chunkIndex": 99, "quote": CHUNK_A[:10]},
                {"text": "这条连段落序号都没有给出来", "quote": CHUNK_A[:10]},
            ]
        }
    )

    result = extract_claims(two_chunks(), chat)

    assert result.stats.dropped == {DROP_UNKNOWN_CHUNK: 2}


def test_claim_length_bounds() -> None:
    chat = StubChat(
        {
            "claims": [
                {"text": "太短", "chunkIndex": 0, "quote": "每天写五百字"},
                {"text": "很长" * 200, "chunkIndex": 0, "quote": "每天写五百字"},
            ]
        }
    )

    result = extract_claims(two_chunks(), chat)

    assert result.stats.dropped == {DROP_TEXT_TOO_SHORT: 1, DROP_TEXT_TOO_LONG: 1}


def test_duplicate_claims_are_counted_not_silently_dropped() -> None:
    chat = StubChat(
        {
            "claims": [
                {
                    "text": "每天写五百字可以累积成十八万字",
                    "chunkIndex": 0,
                    "quote": "每天写五百字",
                    "confidence": 0.6,
                },
                {
                    "text": "每天写五百字可以累积成十八万字",
                    "chunkIndex": 0,
                    "quote": "一年就是十八万字",
                    "confidence": 0.6,
                },
            ]
        }
    )

    result = extract_claims(two_chunks(), chat)

    assert result.stats.kept == 1
    assert result.stats.dropped == {DROP_DUPLICATE: 1}


def test_missing_confidence_defaults_instead_of_dropping() -> None:
    """置信度缺失不该毁掉一条**引用为真**的主张：它只是没法自评，不是不可信。"""
    chat = StubChat(
        {
            "claims": [
                {
                    "text": "深夜写作要先清掉干扰",
                    "chunkIndex": 1,
                    "quote": "先把手机放到另一个房间",
                },
            ]
        }
    )

    result = extract_claims(two_chunks(), chat)

    assert result.stats.kept == 1
    assert result.claims[0].confidence == 0.5


def test_confidence_is_clamped() -> None:
    chat = StubChat(
        {
            "claims": [
                {
                    "text": "写作者应当把目标切小",
                    "chunkIndex": 0,
                    "quote": "把目标切到小得不可能失败",
                    "confidence": 7,
                },
            ]
        }
    )

    assert extract_claims(two_chunks(), chat).claims[0].confidence == 1.0


@pytest.mark.parametrize(
    "payload", ["完全不是 JSON", "", "{}", '{"claims": 3}', '{"claims": [1, 2]}']
)
def test_malformed_output_yields_nothing_instead_of_crashing(payload: str) -> None:
    """格式抖动不该炸掉整轮抽取（与 Agent 解析决策同一条口径）。"""
    result = extract_claims(two_chunks(), StubChat(payload))

    assert result.claims == []
    assert result.stats.proposed == 0


def test_max_posts_bounds_the_number_of_model_calls() -> None:
    """每篇文章一次调用：上限就是成本闸门，必须真的生效。"""
    chunks = [chunk(1, 0, CHUNK_A), chunk(2, 0, CHUNK_B), chunk(3, 0, CHUNK_A)]
    chat = StubChat({"claims": []})

    result = extract_claims(chunks, chat, max_posts=2)

    assert result.stats.posts == 2
    assert len(chat.prompts) == 2


def test_prompt_carries_the_chunk_index_and_title() -> None:
    """模型要能按段落序号引用，提示词里就必须真的给出序号与标题。"""
    chat = StubChat({"claims": []})

    extract_claims(two_chunks(), chat)

    prompt = chat.prompts[0]
    assert "[chunkIndex=0]" in prompt and "[chunkIndex=1]" in prompt
    assert "测试文章" in prompt
    assert CHUNK_B in prompt, "段落原文要进提示词，否则模型没法逐字引用"


def test_bad_bounds_are_rejected_loudly() -> None:
    with pytest.raises(ValueError, match="max_posts"):
        extract_claims(two_chunks(), StubChat({"claims": []}), max_posts=0)
    with pytest.raises(ValueError, match="max_claims_per_chunk"):
        extract_claims(two_chunks(), StubChat({"claims": []}), max_claims_per_chunk=0)


def test_async_path_is_the_same_implementation() -> None:
    """同步入口只是驱动异步实现：两条路的结论必须一致。

    ⚠️ 这个用例**必须是同步的**：同步入口内部用 `asyncio.run`，在事件循环里调用会直接报错
    （这是刻意的守卫，见 `_run_sync` 的注释 —— 在异步上下文里偷偷开循环会阻塞整个服务）。
    """
    payload = {
        "claims": [
            {
                "text": "每天写五百字，一年可以累积十八万字",
                "chunkIndex": 0,
                "quote": "每天写五百字，一年就是十八万字",
                "confidence": 0.9,
            },
        ]
    }

    sync_result = extract_claims(two_chunks(), StubChat(payload))
    async_result = asyncio.run(extract_claims_async(two_chunks(), StubChat(payload)))

    assert [claim.to_dict() for claim in sync_result.claims] == [
        claim.to_dict() for claim in async_result.claims
    ]


def test_sync_entry_refuses_to_run_inside_an_event_loop() -> None:
    """守卫本身也要有用例：否则哪天有人把它换成 `run_until_complete` 也没人发现。"""

    async def call_sync_entry() -> None:
        extract_claims(two_chunks(), StubChat({"claims": []}))

    with pytest.raises(RuntimeError, match="running event loop"):
        asyncio.run(call_sync_entry())


async def test_fake_provider_yields_no_claims_but_does_not_crash() -> None:
    """离线 Fake 模型：抽不出主张是**正常**结果（它不是真的在抽），但链路不能炸。"""
    result = await extract_claims_async(two_chunks(), FakeProvider())

    assert result.claims == []
    assert result.stats.posts == 1


def test_contract_fixture_matches_the_schema() -> None:
    """两侧共读的 fixture 必须能被契约模型解析 —— 否则 Java 侧读到的字段名与 Python 已经不同。

    样例里**故意含两类被丢弃**（`quoteNotFound` 一条主张、`entityNotInText` 一个实体）：
    那两个计数才是「模型不行 vs 证据校验挡下」的判据，空着就守不住它们。
    """
    from pathlib import Path

    from app.schemas.wiki import WikiClaimsResult

    fixture = Path(__file__).resolve().parent / "fixtures" / "wiki_claims_result.json"
    raw = fixture.read_text(encoding="utf-8")
    parsed = WikiClaimsResult.model_validate_json(raw)

    assert len(parsed.claims) == 3
    assert parsed.stats.proposed == 4 and parsed.stats.kept == 3
    assert parsed.stats.dropped == {DROP_QUOTE_NOT_FOUND: 1, "entityNotInText": 1}
    # 每条主张都要能回到**具体版本**的文章与段落
    assert all(claim.post_version and claim.content_hash for claim in parsed.claims)
    assert all(claim.quote in CHUNK_A or claim.quote in CHUNK_B for claim in parsed.claims)

    # 实体：写法差异合并成一个，且每个提及都挂在一句具体主张上
    assert parsed.stats.entity_proposed == 4
    assert parsed.stats.entity_kept == 3, "两条写法重复的实体都留下了（合并前）"
    assert parsed.stats.entities == 2, "合并后是两个实体"
    assert [entity.name for entity in parsed.entities] == ["每天写五百字", "手机干扰"]
    assert parsed.entities[0].count == 2, "写法差异（空白/全角）应当合并"
    assert all(mention.claim_text for entity in parsed.entities for mention in entity.mentions), (
        "每个实体提及都要挂在一条具体主张上 —— 那是它回到证据的那条线"
    )
    assert "完全断网" not in raw, "只出现在被丢弃主张里的实体必须一起被丢掉"
