"""星海问答编排：检索 → 引用 → 提示词 → 模型 → 结论。

这一层只做「编排」，不做算法判断：检索走 `RetrievalPipeline`（与评测台同一条），
模型走 `ChatModel` 协议（Fake 与真实 Provider 同协议）。分成这几步的理由：

1. **先检索、再决定要不要叫模型**：没有候选时直接拒答，省一次调用，也避免模型对着空上下文编。
2. **引用来自检索结果，而不是模型输出**：模型只被要求写 `[1] [2]` 这样的编号，
   真实片段与分数由我们贴回去 —— 否则「引用」就成了模型的一面之词，无法定位回原文。
3. **拒答是数据，不是文案**：`evidence_sufficient=false` + `doneReason=refused`，
   前端据此展示「文章里没有找到依据」，而不是把空答案渲染成空白。

提示词刻意保持最小：只要求「仅依据摘录回答」「摘录里没有就说没有」，不教模型文风 ——
文风属于人设，放在 D2 的写作入口里做，不混进问答。
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass, field

from app.providers.base import ChatModel
from app.providers.models import ChatMessage, ChatResponse, MessageRole, TokenUsage
from app.rag.eval_runner import RetrievedHit
from app.rag.pipeline import IndexedChunk, RetrievalPipeline
from app.schemas.common import Citation, DoneReason, Usage
from app.schemas.qa import MAX_CITATIONS, QaAnswer, QaStreamRequest
from app.schemas.qa_stream import (
    StreamEvent,
    citation_event,
    delta_event,
    done_event,
    meta_event,
)

#: 默认拒答文案：说明「没有依据」而不是「我不会」
DEFAULT_REFUSAL = "这几篇文章里没有找到能回答这个问题的依据。可以换个说法，或者先去写一篇。"

#: 单条引用的片段上限：引用是给人看的定位线索，不是全文复制
DEFAULT_SNIPPET_LENGTH = 200

#: 送进模型的摘录总长度上限：防止一次问句把整库塞进上下文（费用与延迟都不可控）
DEFAULT_MAX_CONTEXT_CHARS = 4000

SYSTEM_PROMPT = (
    "你是「星笺」站点的问答助手。只依据下面给出的文章摘录回答，"
    "不要使用摘录之外的知识，也不要编造细节。"
    "每条结论后面用 [1] [2] 这样的编号标注它来自哪段摘录。"
    "如果摘录不足以回答，就直说「文章里没有找到依据」，不要勉强作答。"
)


@dataclass(frozen=True, slots=True)
class QaSettings:
    """问答的业务参数（不是模型参数 —— 那些在 Provider 配置里）。"""

    max_citations: int = MAX_CITATIONS
    snippet_length: int = DEFAULT_SNIPPET_LENGTH
    max_context_chars: int = DEFAULT_MAX_CONTEXT_CHARS
    refusal_message: str = DEFAULT_REFUSAL
    temperature: float | None = 0.2
    max_tokens: int | None = None

    def __post_init__(self) -> None:
        if not 1 <= self.max_citations <= MAX_CITATIONS:
            raise ValueError(f"max_citations 必须在 1..{MAX_CITATIONS}")
        if self.snippet_length < 20:
            raise ValueError("snippet_length 太小：引用要能定位回原文")
        if self.max_context_chars < self.snippet_length:
            raise ValueError("max_context_chars 不能小于 snippet_length：至少要放得下一段摘录")


@dataclass(frozen=True, slots=True)
class _Excerpt:
    """一段送进模型的摘录：编号即提示词里的 `[n]`，也是引用列表的顺序。"""

    number: int
    chunk: IndexedChunk
    snippet: str
    score: float


@dataclass(slots=True)
class QaService:
    """一次问答的完整编排。无状态（可并发复用），调用方注入检索管道与模型。"""

    pipeline: RetrievalPipeline
    chat: ChatModel
    settings: QaSettings = field(default_factory=QaSettings)
    _chat_calls: int = field(default=0, init=False)
    _by_id: dict[str, IndexedChunk] = field(default_factory=dict, init=False)

    def __post_init__(self) -> None:
        # chunk_id 唯一性由 `RetrievalPipeline` 保证（它在构造时就拦下重复语料），
        # 这里只建索引，不重复校验
        self._by_id = {chunk.chunk_id: chunk for chunk in self.pipeline.corpus}

    @property
    def chat_calls(self) -> int:
        """模型调用次数：测试用它证明「没有依据时不会白花一次调用」。"""
        return self._chat_calls

    async def answer(self, request: QaStreamRequest) -> QaAnswer:
        outcome = await self.pipeline.retrieve(request.question, top_k=request.top_k)

        # 检索层说「没有候选」就是没有依据：直接拒答，不叫模型
        if outcome.refused or not outcome.hits:
            return self._refusal(outcome.latency_ms)

        excerpts = self._excerpts(outcome.hits)
        citations = [self._citation(excerpt) for excerpt in excerpts]
        messages = self._messages(request.question, excerpts)
        response = await self.chat.chat(
            messages, temperature=self.settings.temperature, max_tokens=self.settings.max_tokens
        )
        self._chat_calls += 1

        usage = Usage(
            prompt_tokens=response.usage.prompt_tokens,
            completion_tokens=response.usage.completion_tokens,
            total_tokens=response.usage.total_tokens,
            # 端到端耗时以检索 + 模型的总时长为准（单看模型耗时会低估用户等待）
            latency_ms=int(outcome.latency_ms) + response.usage.latency_ms,
            model=response.usage.model,
        )

        if response.refused:
            # 模型自己说答不了（内容过滤或空输出）：按拒答处理，但**保留引用**，
            # 因为「找到相关段落但答不出」与「什么都没找到」对用户是不同的信息
            return QaAnswer(
                answer=response.text.strip() or self.settings.refusal_message,
                citations=citations,
                done_reason=DoneReason.REFUSED,
                usage=usage,
                evidence_sufficient=False,
            )

        return QaAnswer(
            answer=response.text,
            citations=citations,
            done_reason=DoneReason.STOP,
            usage=usage,
            evidence_sufficient=True,
        )

    async def stream(self, request: QaStreamRequest) -> AsyncIterator[StreamEvent]:
        """流式问答：按 `schemas/qa_stream.py` 定死的顺序吐事件。

        与非流式版本**共用同一套检索、摘录与拒答判定**，区别只在「模型怎么被调用」：
        - 支持流式的模型：边生成边吐 `delta`；
        - 只支持一次性调用的模型：拿到完整答案后吐**一个** `delta`
          （前端只多一次渲染，不会少一块内容）。

        事件顺序刻意是「meta → citation → delta → done」：引用由检索决定，**不必等模型**，
        先发出去就能让前端在正文还在生成时先把定位线索渲染好。
        """
        yield meta_event(
            model=self._model_tag(),
            question_length=len(request.question),
            top_k=request.top_k,
        )

        outcome = await self.pipeline.retrieve(request.question, top_k=request.top_k)
        if outcome.refused or not outcome.hits:
            refusal = self._refusal(outcome.latency_ms)
            yield delta_event(refusal.answer)
            yield done_event(
                answer=refusal.answer,
                done_reason=refusal.done_reason,
                usage=refusal.usage,
                evidence_sufficient=False,
            )
            return

        excerpts = self._excerpts(outcome.hits)
        citations = [self._citation(excerpt) for excerpt in excerpts]
        for citation in citations:
            yield citation_event(citation)

        messages = self._messages(request.question, excerpts)
        async for event in self._stream_answer(messages, len(citations), int(outcome.latency_ms)):
            yield event

    async def _stream_answer(
        self,
        messages: list[ChatMessage],
        citation_count: int,
        retrieval_ms: int,
    ) -> AsyncIterator[StreamEvent]:
        """模型这一段：能流就流，不能流就一次性回答，再统一收尾。"""
        if callable(getattr(self.chat, "stream_chat", None)):
            text = ""
            usage: TokenUsage | None = None
            finish_reason = "stop"
            async for chunk in self.chat.stream_chat(  # type: ignore[attr-defined]
                messages,
                temperature=self.settings.temperature,
                max_tokens=self.settings.max_tokens,
            ):
                if chunk.text:
                    text += chunk.text
                    yield delta_event(chunk.text)
                if chunk.finish_reason:
                    finish_reason = chunk.finish_reason
                if chunk.usage is not None:
                    usage = chunk.usage
            self._chat_calls += 1
            yield self._final_event(
                text=text,
                finish_reason=finish_reason,
                usage=usage or TokenUsage(model=self._model_tag()),
                retrieval_ms=retrieval_ms,
                citation_count=citation_count,
            )
            return

        # 没有流式能力：退回一次性调用。**这不是错误路径** —— 少一次增量，内容一样完整。
        response: ChatResponse = await self.chat.chat(
            messages, temperature=self.settings.temperature, max_tokens=self.settings.max_tokens
        )
        self._chat_calls += 1
        if response.text:
            yield delta_event(response.text)
        yield self._final_event(
            text=response.text,
            finish_reason=response.finish_reason,
            usage=response.usage,
            retrieval_ms=retrieval_ms,
            citation_count=citation_count,
        )

    def _final_event(
        self,
        *,
        text: str,
        finish_reason: str,
        usage: TokenUsage,
        retrieval_ms: int,
        citation_count: int,
    ) -> StreamEvent:
        """收尾：把「模型自己拒答」与「正常说完」分开，两者都不吞掉已经发出去的引用。"""
        refused = finish_reason == "content_filter" or not text.strip()
        answer = text.strip() or self.settings.refusal_message
        return done_event(
            answer=answer,
            done_reason=DoneReason.REFUSED if refused else DoneReason.STOP,
            usage=Usage(
                prompt_tokens=usage.prompt_tokens,
                completion_tokens=usage.completion_tokens,
                total_tokens=usage.total_tokens,
                latency_ms=retrieval_ms + usage.latency_ms,
                model=usage.model or self._model_tag(),
            ),
            evidence_sufficient=not refused,
        )

    def _model_tag(self) -> str:
        """从模型对象上取一个可展示的标识（Fake 与真实 Provider 都有）。"""
        for attribute in ("MODEL_TAG", "model", "name"):
            value = getattr(self.chat, attribute, None)
            if isinstance(value, str) and value:
                return value
        return "unknown"

    def _messages(self, question: str, excerpts: list[_Excerpt]) -> list[ChatMessage]:
        return [
            ChatMessage(role=MessageRole.SYSTEM, content=SYSTEM_PROMPT),
            ChatMessage(role=MessageRole.USER, content=_user_prompt(question, excerpts)),
        ]

    def _refusal(self, latency_ms: float) -> QaAnswer:
        return QaAnswer(
            answer=self.settings.refusal_message,
            citations=[],
            done_reason=DoneReason.REFUSED,
            usage=Usage(latency_ms=int(latency_ms)),
            evidence_sufficient=False,
        )

    def _excerpts(self, hits: list[RetrievedHit]) -> list[_Excerpt]:
        """挑出要送进模型的摘录：按名次取，总长度封顶。"""
        chosen: list[_Excerpt] = []
        budget = self.settings.max_context_chars
        for hit in hits:
            if len(chosen) >= self.settings.max_citations:
                break
            chunk = self._by_id.get(hit.chunk_id)
            if chunk is None:
                # 命中回不到语料说明索引与语料不是同一批：宁可报错也不要拼出假引用
                raise ValueError(f"检索命中不在语料里：{hit.chunk_id}（需要重建索引）")
            snippet = _snippet(chunk, self.settings.snippet_length)
            if len(snippet) > budget and chosen:
                # 预算不够就停在这里：宁可少给一段，也不要把最后一段截成半句话
                break
            chosen.append(
                _Excerpt(
                    number=len(chosen) + 1,
                    chunk=chunk,
                    snippet=snippet,
                    score=float(hit.score),
                )
            )
            budget -= len(snippet)
        return chosen

    @staticmethod
    def _citation(excerpt: _Excerpt) -> Citation:
        chunk = excerpt.chunk
        raw_index = chunk.payload.get("chunkIndex")
        return Citation(
            post_id=chunk.post_id,
            title=chunk.title or f"文章 {chunk.post_id}",
            # payload 的值是 object：类型不对时回退 0，而不是让 int() 在运行期抛错
            chunk_index=raw_index if isinstance(raw_index, int) and raw_index >= 0 else 0,
            snippet=excerpt.snippet,
            score=excerpt.score,
        )


def _snippet(chunk: IndexedChunk, limit: int) -> str:
    """取片段：优先用 payload 里的原始正文（不含标题前缀），回退到切掉首行。"""
    raw = str(chunk.payload.get("text") or "")
    if not raw:
        # `text` 是「标题\\n正文」，去掉标题那一行才是原文片段
        raw = chunk.text.split("\n", 1)[1] if "\n" in chunk.text else chunk.text
    raw = raw.strip()
    return raw if len(raw) <= limit else raw[:limit].rstrip() + "…"


def _user_prompt(question: str, excerpts: list[_Excerpt]) -> str:
    lines = [f"问题：{question}", "", "文章摘录："]
    for excerpt in excerpts:
        title = excerpt.chunk.title or excerpt.chunk.post_id
        lines.append(f"[{excerpt.number}]《{title}》：{excerpt.snippet}")
    lines.append("")
    lines.append("请仅依据以上摘录回答，并用 [编号] 标注来源。")
    return "\n".join(lines)
