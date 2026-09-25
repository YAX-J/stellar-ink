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

import time
from collections.abc import AsyncIterator
from dataclasses import dataclass, field

from app.providers.base import ChatModel, model_tag_of
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

#: 输出被 `max_tokens` 截断且一个字都没拿到时的文案。
#: 不能说成「模型拒答」：那会让人去查安全过滤或提示词，而真正的原因是预算不够
TRUNCATED_MESSAGE = (
    "模型还没写出正文就用完了 token 预算（推理模型的思考也占预算）。"
    "请调大该角色的 maxTokens，或把问题问得再短一些。"
)

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


def _elapsed_ms(started: float) -> int:
    return max(0, int((time.perf_counter() - started) * 1000))


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


@dataclass(frozen=True, slots=True)
class _ModelOutcome:
    """模型的输出翻成契约字段。"""

    text: str
    done_reason: DoneReason
    evidence_sufficient: bool


def model_outcome(text: str, finish_reason: str, *, refusal_message: str) -> _ModelOutcome:
    """**非流式与流式共用同一条判定**（曾经两处各写一遍，其中一处把截断当成了拒答）。

    三种情况，两个维度（有没有内容 / 有没有依据）分开表达：
    - 有内容：`stop`；若是被截断（`length`）则如实标 `length` —— 半截答案也不该谎称完整；
    - 空且 `length`：**预算被烧完**，不是拒答。推理模型先写 `reasoning_content`，
      那段也占 `completion_tokens`，预算小了就是这个形态；
    - 空且正常结束 / 内容过滤：这才是模型拒答。
    """
    truncated = finish_reason == "length"
    if text.strip():
        return _ModelOutcome(
            text=text,
            done_reason=DoneReason.LENGTH if truncated else DoneReason.STOP,
            evidence_sufficient=True,
        )
    if truncated:
        return _ModelOutcome(
            text=TRUNCATED_MESSAGE,
            done_reason=DoneReason.LENGTH,
            evidence_sufficient=False,
        )
    return _ModelOutcome(
        text=refusal_message,
        done_reason=DoneReason.REFUSED,
        evidence_sufficient=False,
    )


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

        if response.refused or response.truncated:
            # 模型自己说答不了（内容过滤 / 空输出），或者预算被截断：两种都不算「答完了」，
            # 但**保留引用** —— 「找到相关段落却答不出」与「什么都没找到」对用户是不同的信息
            model_result = model_outcome(
                response.text,
                response.finish_reason,
                refusal_message=self.settings.refusal_message,
            )
            return QaAnswer(
                answer=model_result.text,
                citations=citations,
                done_reason=model_result.done_reason,
                usage=usage,
                # 依据够不够与回答完不完整是两件事：写了一半被打断时依据仍然是够的
                evidence_sufficient=model_result.evidence_sufficient,
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
        started = time.perf_counter()
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
                generation_ms=_elapsed_ms(started),
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
            # 两个来源取较大值（自己量的 + 上游报的），不会漏报
            generation_ms=_elapsed_ms(started),
        )

    def _final_event(
        self,
        *,
        text: str,
        finish_reason: str,
        usage: TokenUsage,
        retrieval_ms: int,
        citation_count: int,
        generation_ms: int | None = None,
    ) -> StreamEvent:
        """收尾：把「模型自己拒答」「预算截断」与「正常说完」分开，三者都不吞掉已发出的引用。

        耗时取「检索 + 生成」：流式的增量块里 `usage.latency_ms` 恒为 0
        （那是**单块**的耗时，不是整段生成的），照抄它会让一个跑了 20 秒的回答
        在响应里显示成几毫秒 —— 前端与运维都会据此判断「这个链路很快」。
        两个来源取**较大值**：自己量的耗时不会漏报，上游真报了大数也照收。
        """
        outcome = model_outcome(text, finish_reason, refusal_message=self.settings.refusal_message)
        model_ms = max(generation_ms or 0, usage.latency_ms)
        return done_event(
            answer=outcome.text,
            done_reason=outcome.done_reason,
            usage=Usage(
                prompt_tokens=usage.prompt_tokens,
                completion_tokens=usage.completion_tokens,
                total_tokens=usage.total_tokens,
                latency_ms=retrieval_ms + model_ms,
                model=usage.model or self._model_tag(),
            ),
            evidence_sufficient=outcome.evidence_sufficient,
        )

    def _model_tag(self) -> str:
        """从模型对象上取一个可展示的标识（真实 Provider 与离线桩都有）。"""
        return model_tag_of(self.chat)

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
