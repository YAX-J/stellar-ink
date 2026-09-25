"""问答编排测试：引用从哪来、什么时候拒答、提示词里放了什么。

这一层最容易出的不是崩溃，而是**看起来正常的错**：
引用指向没被用过的段落、没有依据时模型照样编、摘录超预算悄悄截半句话。
所以测试都盯在这些「不报错但错」的地方，并用计数桩证明「不该花的调用没花」。
"""

from __future__ import annotations

import pytest

from app.providers.errors import ProviderError
from app.providers.models import ChatMessage, ChatResponse, TokenUsage
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline, build_corpus
from app.rag.qa import QaService, QaSettings
from app.schemas.common import DoneReason
from app.schemas.qa import QaStreamRequest


class _Post:
    def __init__(self, post_id: int, title: str, plain: str) -> None:
        self.post_id = post_id
        self.title = title
        self.plain = plain


class _Embedder:
    """假嵌入：按文本哈希铺开固定维度向量（与 FakeProvider 同思路，够驱动检索）。"""

    def __init__(self, dimension: int = 32) -> None:
        self._dimension = dimension

    def _vector(self, text: str) -> list[float]:
        import hashlib
        import math

        digest = hashlib.sha256(text.encode("utf-8")).digest()
        values = [(byte / 255.0) - 0.5 for byte in digest]
        values = [values[index % len(values)] for index in range(self._dimension)]
        norm = math.sqrt(sum(value * value for value in values)) or 1.0
        return [value / norm for value in values]

    async def embed(self, texts):
        from app.providers.models import EmbeddingResponse

        return EmbeddingResponse(
            vectors=[self._vector(text) for text in texts],
            dimension=self._dimension,
            usage=TokenUsage(),
        )


class _Chat:
    """可控对话桩：记录收到的消息，按脚本返回；`calls` 用来证明「没有依据时没白调」。"""

    def __init__(self, text: str = "答案是 [1]。", *, finish_reason: str = "stop") -> None:
        self.text = text
        self.finish_reason = finish_reason
        self.calls: list[list[ChatMessage]] = []

    async def chat(self, messages, *, temperature=None, max_tokens=None) -> ChatResponse:
        self.calls.append(list(messages))
        return ChatResponse(
            text=self.text,
            finish_reason=self.finish_reason,
            usage=TokenUsage.of(100, 20, latency_ms=7, model="stub-chat"),
        )


def _posts() -> list[_Post]:
    return [
        _Post(
            1,
            "在算法的洪流里做一个缓慢的人",
            "写得快不算活着。真正留下来的句子，都是在慢里熬出来的。",
        ),
        _Post(2, "一年写十八万字的方法", "不追求每天都写得好，只追求每天都写。数量会变成习惯。"),
        _Post(3, "海边的路由器", "信号很差，睡眠很好。海风把窗帘吹得像一面帆。"),
    ]


def _service(chat=None, *, settings=None, config=None) -> QaService:
    corpus = build_corpus(_posts())
    pipeline = RetrievalPipeline(
        corpus=corpus,
        config=config or RetrievalConfig(enable_sparse=True, enable_dense=True),
        embedder=_Embedder(),
    )
    return QaService(pipeline=pipeline, chat=chat or _Chat(), settings=settings or QaSettings())


def _request(question: str = "作者为什么坚持写博客？", top_k: int = 5) -> QaStreamRequest:
    return QaStreamRequest(question=question, top_k=top_k)


async def test_answer_carries_citations_with_scores_and_snippets() -> None:
    service = _service()

    answer = await service.answer(_request())

    assert answer.evidence_sufficient is True
    assert answer.done_reason is DoneReason.STOP
    assert answer.answer == "答案是 [1]。", "模型输出不该被改写"
    assert answer.citations, "有依据时必须给出引用"
    first = answer.citations[0]
    assert first.post_id in {1, 2, 3}
    assert first.title, "引用要带标题：前端要显示它"
    assert first.snippet and len(first.snippet) <= service.settings.snippet_length
    assert first.score is not None
    # 片段来自正文本身，不含「标题\n」那层检索用前缀
    assert not first.snippet.startswith(first.title)
    assert answer.usage.model == "stub-chat"
    assert answer.usage.total_tokens == 120


async def test_citations_only_include_excerpts_sent_to_the_model() -> None:
    """引用必须与送进模型的摘录一一对应，否则用户点开引用会看到没被用过的段落。"""
    chat = _Chat()
    service = _service(chat, settings=QaSettings(max_citations=2))

    answer = await service.answer(_request(top_k=5))

    sent = chat.calls[0][1].content
    assert len(answer.citations) <= 2
    for citation in answer.citations:
        assert citation.snippet[:20] in sent, "引用片段必须出现在提示词里"


async def test_prompt_contains_question_and_numbered_excerpts() -> None:
    chat = _Chat()
    service = _service(chat)

    await service.answer(_request("作者为什么坚持写博客？"))

    system, user = chat.calls[0]
    assert system.role == "system"
    assert "只依据" in system.content, "系统提示要约束「不得编造」"
    assert "作者为什么坚持写博客？" in user.content
    assert "[1]" in user.content, "摘录要有编号，模型才能标注来源"
    assert "《" in user.content, "摘录要带文章标题"


async def test_no_evidence_refuses_without_calling_the_model() -> None:
    """没有候选时直接拒答：省一次调用，也不给模型编造的机会。"""
    chat = _Chat()
    service = _service(
        chat,
        config=RetrievalConfig(enable_sparse=True, enable_dense=False, min_score=10_000.0),
    )

    answer = await service.answer(_request())

    assert answer.evidence_sufficient is False
    assert answer.done_reason is DoneReason.REFUSED
    assert answer.citations == []
    assert "没有找到" in answer.answer
    assert chat.calls == [], "没有依据就不该调用模型"
    assert service.chat_calls == 0


async def test_model_refusal_keeps_the_citations() -> None:
    """模型答不出但确实找到了相关段落：这是两种不同的信息，引用要保留。"""
    chat = _Chat(text="", finish_reason="content_filter")
    service = _service(chat)

    answer = await service.answer(_request())

    assert answer.done_reason is DoneReason.REFUSED
    assert answer.evidence_sufficient is False
    assert answer.citations, "找到了段落就该把引用摆出来，让用户自己判断"
    assert answer.answer, "拒答也要有可展示的文案"


async def test_snippet_is_truncated_with_ellipsis() -> None:
    chat = _Chat()
    service = _service(chat, settings=QaSettings(snippet_length=20))

    answer = await service.answer(_request())

    assert all(len(citation.snippet) <= 21 for citation in answer.citations)
    assert any(citation.snippet.endswith("…") for citation in answer.citations)


async def test_context_budget_caps_how_many_excerpts_are_sent() -> None:
    chat = _Chat()
    # 预算 = 一段的长度：只放得下第一段，后面的停手而不是截半句
    service = _service(chat, settings=QaSettings(snippet_length=30, max_context_chars=30))

    answer = await service.answer(_request(top_k=5))

    assert len(answer.citations) == 1, "预算只够一段，就不该把整库都塞进提示词"
    assert len(chat.calls[0][1].content) < 300


async def test_citation_cap_is_respected() -> None:
    service = _service(settings=QaSettings(max_citations=1))

    answer = await service.answer(_request(top_k=5))

    assert len(answer.citations) == 1


async def test_settings_are_validated() -> None:
    with pytest.raises(ValueError, match="max_citations"):
        QaSettings(max_citations=0)
    with pytest.raises(ValueError, match="snippet_length"):
        QaSettings(snippet_length=5)
    with pytest.raises(ValueError, match="max_context_chars"):
        QaSettings(max_context_chars=10)


async def test_unknown_hit_fails_loudly() -> None:
    """索引与语料不是同一批时：宁可报错，也不要拼出一条指向别处的引用。"""
    service = _service()
    original = service.pipeline.corpus
    service._by_id = {}  # 模拟「命中回不到语料」
    assert original

    with pytest.raises(ValueError, match="不在语料里"):
        await service.answer(_request())


async def test_retriever_failure_is_not_swallowed() -> None:
    """检索报错要冒泡：把故障说成「没有依据」会误导产品判断。"""

    class _Broken(_Embedder):
        async def embed(self, texts):
            raise ProviderError("上游挂了")

    corpus = build_corpus(_posts())
    pipeline = RetrievalPipeline(
        corpus=corpus,
        config=RetrievalConfig(enable_sparse=False, enable_dense=True),
        embedder=_Broken(),
    )
    service = QaService(pipeline=pipeline, chat=_Chat())

    with pytest.raises(ProviderError):
        await service.answer(_request())


def test_corpus_with_duplicate_chunk_ids_is_rejected_upstream() -> None:
    """重复 chunk_id 由检索管道在构造时拦下（引用靠它回查，重复即张冠李戴）。"""
    corpus = build_corpus(_posts())

    with pytest.raises(ValueError, match="重复的 chunk_id"):
        RetrievalPipeline(
            corpus=[corpus[0], corpus[0]],
            config=RetrievalConfig(enable_sparse=True, enable_dense=False),
        )
