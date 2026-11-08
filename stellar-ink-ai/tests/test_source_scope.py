"""明确限定来源时，其他同主题文章不能替代指定原文。"""

from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.rag.eval_runner import RetrievalOutcome, RetrievedHit
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline, build_corpus
from app.rag.qa import QaService
from app.rag.source_scope import exclusive_titles
from app.schemas.qa import QaStreamRequest
from tests.test_qa import _Chat


@pytest.mark.parametrize(
    ("question", "expected"),
    [
        ("请只根据《指定原文》回答。", ("指定原文",)),
        ("仅依据《甲》和《乙》比较。", ("甲", "乙")),
        ("只根据《甲》说明《乙》的影响", ("甲",)),
        ("只根据《读《夜航西飞》三次》回答。", ("读《夜航西飞》三次",)),
        ("作者读《夜航西飞》三次的感受？", ()),
        ("23秒接口排障文章怎么调整等待？", ()),
        ("只根据尚未说完的标题《甲", ()),
    ],
)
def test_only_explicit_source_constraints_are_recognized(question, expected):
    assert exclusive_titles(question) == expected


def service():
    docs = [
        SimpleNamespace(post_id=1, title="指定原文", plain="等待时间由六十秒改为五秒。", tags=[]),
        SimpleNamespace(post_id=2, title="其他笔记", plain="等待时间由一百秒改为十秒。", tags=[]),
    ]
    pipeline = RetrievalPipeline(
        corpus=build_corpus(docs), config=RetrievalConfig(enable_dense=False)
    )
    chat = _Chat("指定原文说等待改为五秒。[1]")
    return QaService(pipeline=pipeline, chat=chat), chat


@pytest.mark.parametrize("streaming", [False, True])
async def test_only_requested_source_reaches_the_model_and_citations(streaming):
    qa, chat = service()
    request = QaStreamRequest(question="仅根据《指定原文》说明等待从多少改到多少？")
    if streaming:
        events = [event async for event in qa.stream(request)]
        citations = [e.payload["citation"] for e in events if e.type == "citation"]
        assert citations and all(c["postId"] == 1 for c in citations)
    else:
        answer = await qa.answer(request)
        assert answer.citations and all(c.post_id == 1 for c in answer.citations)
    assert "一百秒" not in chat.calls[0][1].content
    assert "六十秒" in chat.calls[0][1].content


@pytest.mark.parametrize("streaming", [False, True])
async def test_missing_source_refuses_before_search_and_generation(streaming, monkeypatch):
    qa, chat = service()
    monkeypatch.setattr(
        RetrievalPipeline,
        "retrieve",
        AsyncMock(side_effect=AssertionError("来源缺失不应发起嵌入或检索")),
    )
    request = QaStreamRequest(question="请只根据《缺失原文》说明等待时间")
    if streaming:
        events = [event async for event in qa.stream(request)]
        done = events[-1].payload
        assert done["doneReason"] == "refused" and done["usage"]["modelCalls"] == []
        assert not any(e.type == "citation" for e in events)
    else:
        answer = await qa.answer(request)
        assert answer.done_reason == "refused" and not answer.citations
        assert answer.usage.total_tokens == 0 and answer.usage.model_calls == []
    assert not chat.calls


async def test_withdrawn_source_does_not_pass_the_source_constraint():
    qa, chat = service()
    qa.pipeline.refresh_corpus = lambda: [c for c in qa.pipeline.corpus if c.post_id == 2]
    answer = await qa.answer(QaStreamRequest(question="只根据《指定原文》说明等待时间"))
    assert answer.done_reason == "refused" and not chat.calls


async def test_other_source_hits_do_not_override_a_missing_requested_hit(monkeypatch):
    qa, chat = service()
    other = next(c for c in qa.pipeline.corpus if c.post_id == 2)
    monkeypatch.setattr(
        RetrievalPipeline,
        "retrieve",
        AsyncMock(
            return_value=RetrievalOutcome(
                posts=[2],
                hits=[RetrievedHit(other.chunk_id, 2, 1, other.text)],
            )
        ),
    )
    answer = await qa.answer(QaStreamRequest(question="只根据《指定原文》说明等待时间"))
    assert answer.done_reason == "refused" and not answer.citations and not chat.calls
