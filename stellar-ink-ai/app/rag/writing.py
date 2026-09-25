"""星笺 Copilot（写作建议）：草稿 → 提示词 → 模型 → 候选列表。

红线（`development-workflow.md` §7.4）：**建议只返回候选文本，绝不直接写正文**。
采纳与否由前端差异预览 + 作者确认决定，文章写入仍走既有 `/posts/**`。
这一层因此是纯函数式的：输入草稿、输出候选，不碰数据库、不碰文件。

两处刻意的取舍：
- **草稿只进本次请求**：不外发到除模型以外的任何地方、不进索引（它是未发表的私有内容）；
  超长草稿按「首 + 尾」截断后送模型 —— 开头定调子、结尾是续写的接点，中间省掉最划算。
- **候选解析容错但不将就**：先按 JSON 解析（结构化输出的首选），失败再退到 `---` 分隔的纯文本；
  两条路都解析不出内容时**报错**而不是返回空候选 —— 「模型没按格式回答」与「没有建议」是两件事。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field

from app.providers.base import ChatModel
from app.providers.models import ChatMessage, ChatResponse, MessageRole, TokenUsage
from app.schemas.common import Usage
from app.schemas.writing import (
    MAX_CANDIDATES,
    WritingCandidate,
    WritingSuggestRequest,
    WritingSuggestResult,
    WritingTask,
    WritingTone,
)

#: 送进模型的草稿上限：超出部分按「首 + 尾」保留，中间用省略标记
DEFAULT_MAX_DRAFT_CHARS = 6000
#: 截断后首尾各留多少
_HEAD_RATIO = 0.7

SYSTEM_PROMPT = (
    "你是「星笺」的写作助手。只用中文回答，只输出候选本身，不要解释你的过程。"
    "不要替作者决定最终文字 —— 你给的是选项，采纳与否由作者自己判断。"
)

#: 每类任务的指令。写清楚「要什么形态」，比让模型猜有效得多。
TASK_PROMPTS: dict[WritingTask, str] = {
    WritingTask.POLISH: (
        "润色下面的草稿：保留原意与段落结构，只改表达（去掉空话、拆开长句、让语气统一）。"
    ),
    WritingTask.CONTINUE: (
        "接着下面的草稿往下写一到两段：延续它的语气与视角，不要重复已写过的内容。"
    ),
    WritingTask.TITLE: "为下面的草稿拟标题：不超过 20 字，具体、克制，不要用感叹号与营销腔。",
    WritingTask.OUTLINE: "为下面的草稿整理提纲：用 3-5 条 `- ` 开头的要点，覆盖已有内容的结构。",
    WritingTask.TAGS: "为下面的草稿给出标签：每条一个词或短语，用英文逗号分隔，3-5 个，不要带 #。",
    WritingTask.SUMMARY: "用一到两句话概括下面的草稿：只写它说了什么，不要评价。",
}

#: 风格目标 → 提示词补充
TONE_HINTS: dict[WritingTone, str] = {
    WritingTone.KEEP: "保持原有语气，不要添加新的风格。",
    WritingTone.RESTRAINED: "更克制：少用形容词与程度词，把感受换成具体的场景。",
    WritingTone.COLLOQUIAL: "更口语：像跟朋友讲话，允许短句与语气词。",
    WritingTone.CONCISE: "更简：能删的都删掉，句子短下来，意思不变。",
}

#: 只要一个值的任务：这些任务下「单段纯文本」是合理回答（其余任务必须给多段或 JSON）
SINGLE_VALUE_TASKS = frozenset({WritingTask.TITLE, WritingTask.TAGS, WritingTask.SUMMARY})

#: 要求模型按 JSON 数组回答：解析路径最短，也最不容易歧义
_JSON_TAIL = (
    '请只输出一个 JSON 数组，元素形如 {{"text": "候选内容", "rationale": "为什么这么改"}}；'
    "共 {count} 条，不要输出数组以外的任何字符。"
)


@dataclass(frozen=True, slots=True)
class WritingSettings:
    """Copilot 的业务参数（模型参数在 Provider 配置里）。"""

    max_draft_chars: int = DEFAULT_MAX_DRAFT_CHARS
    temperature: float = 0.7
    max_tokens: int | None = None

    def __post_init__(self) -> None:
        if self.max_draft_chars < 200:
            raise ValueError("max_draft_chars 太小：至少要放得下一段草稿")


@dataclass(slots=True)
class WritingCopilot:
    """写作建议编排：无状态（可并发复用），调用方注入对话模型。"""

    chat: ChatModel
    settings: WritingSettings = field(default_factory=WritingSettings)
    _chat_calls: int = field(default=0, init=False)

    @property
    def chat_calls(self) -> int:
        return self._chat_calls

    async def suggest(self, request: WritingSuggestRequest) -> WritingSuggestResult:
        messages = [
            ChatMessage(role=MessageRole.SYSTEM, content=SYSTEM_PROMPT),
            ChatMessage(
                role=MessageRole.USER,
                content=_user_prompt(request, self.settings.max_draft_chars),
            ),
        ]
        response = await self.chat.chat(
            messages,
            temperature=self.settings.temperature,
            max_tokens=self.settings.max_tokens,
        )
        self._chat_calls += 1

        if response.truncated and not response.text.strip():
            # 一个字都没拿到且 finish_reason=length：这是**预算不够**，不是模型不配合。
            # 报成「解析不出候选」的话，作者会以为自己写的提示词有问题，
            # 而真相是推理模型的思考也占 max_tokens（实测 deepseek-flash 会先写一大段 reasoning）。
            raise ValueError(
                "模型还没写出候选就用完了 token 预算（推理模型的思考也占预算）："
                "请调大 chat 角色的 maxTokens",
            )

        candidates = parse_candidates(
            response.text,
            limit=request.candidate_count,
            expect_single=request.task in SINGLE_VALUE_TASKS,
        )
        return WritingSuggestResult(
            task=request.task,
            candidates=candidates,
            usage=Usage(
                prompt_tokens=response.usage.prompt_tokens,
                completion_tokens=response.usage.completion_tokens,
                total_tokens=response.usage.total_tokens,
                latency_ms=response.usage.latency_ms,
                model=response.usage.model,
            ),
        )


def parse_candidates(
    raw: str, *, limit: int, expect_single: bool = False
) -> list[WritingCandidate]:
    """把模型输出解析成候选列表。

    优先 JSON（结构化输出的首选），失败退到 `---` 分隔；两种都拿不到内容时报错 ——
    「模型没按格式回答」必须让人看见，不能悄悄变成「没有建议」。

    `expect_single` 表示这个任务本来就只该有一个值（标题/标签/摘要）：
    此时单段纯文本可以接受；而润色/续写/提纲没有分隔符时就**不能**把整段解释文字
    当成候选 —— 那通常意味着模型在解释自己为什么不做。
    """
    if limit <= 0:
        raise ValueError("limit 必须为正")
    text = (raw or "").strip()
    if not text:
        raise ValueError("模型返回为空：没有候选可用（不是「没有建议」，是调用出了问题）")

    parsed = _from_json(text)
    if parsed is None:
        parsed = _from_blocks(text, allow_single=expect_single)
    picked = [candidate for candidate in parsed if candidate.text.strip()][:limit]
    if not picked:
        raise ValueError("模型输出里解析不出任何候选（格式不符，需要检查提示词或换模型）")
    return picked


def _from_json(text: str) -> list[WritingCandidate] | None:
    """从输出里挑出 JSON 数组（允许前后有解释性文字）。"""
    start = text.find("[")
    end = text.rfind("]")
    if start < 0 or end <= start:
        return None
    try:
        payload = json.loads(text[start : end + 1])
    except ValueError:
        return None
    if not isinstance(payload, list):
        return None

    candidates: list[WritingCandidate] = []
    for item in payload:
        if isinstance(item, str):
            candidates.append(WritingCandidate(text=item.strip()))
        elif isinstance(item, dict):
            body = str(item.get("text") or item.get("content") or "").strip()
            if not body:
                continue
            rationale = item.get("rationale") or item.get("reason")
            candidates.append(
                WritingCandidate(
                    text=body,
                    rationale=str(rationale).strip() if rationale else None,
                )
            )
    return candidates or None


def _from_blocks(text: str, *, allow_single: bool) -> list[WritingCandidate]:
    """退路：按 `---` 分隔的纯文本。每段首行若形如「理由：…」则抽成 rationale。"""
    blocks = [block.strip() for block in text.split("---")]
    if not allow_single and len(blocks) < 2:
        # 没有分隔符又没有 JSON：多半是模型在解释自己，不该当成候选
        return []
    candidates: list[WritingCandidate] = []
    for block in blocks:
        if not block:
            continue
        lines = [line.strip() for line in block.splitlines() if line.strip()]
        rationale = None
        if lines and lines[0].startswith(("理由：", "理由:")):
            rationale = lines[0].split("：", 1)[-1].split(":", 1)[-1].strip() or None
            lines = lines[1:]
        body = "\n".join(lines).strip()
        if body:
            candidates.append(WritingCandidate(text=body, rationale=rationale))
    return candidates


def _user_prompt(request: WritingSuggestRequest, max_draft_chars: int) -> str:
    parts = [TASK_PROMPTS[request.task]]
    hint = request.instruction.strip() if request.instruction else TONE_HINTS[request.tone]
    if hint:
        parts.append(f"补充要求：{hint}")
    if request.draft.strip():
        parts.append("草稿：\n" + _truncate(request.draft, max_draft_chars))
    parts.append(_JSON_TAIL.format(count=min(request.candidate_count, MAX_CANDIDATES)))
    return "\n\n".join(parts)


class FakeCopilotChat:
    """离线自测用的对话桩：**按提示词要求的 JSON 格式**回答。

    为什么需要它（而不是直接用 `FakeProvider`）：`FakeProvider` 是通用回显桩，
    它把整段提示词原样吐回来 —— 对问答够用，但对 Copilot 会让解析层判定「格式不符」，
    于是离线环境下所有润色/续写请求都以 502 结束。功能看起来是坏的，
    而这跟链路有没有接对毫无关系。

    它**不做任何改写**（没有模型能力就不要假装有）：候选就是草稿的句子切片，
    `rationale` 里写明「离线自测」，前端也会通过 `usage.model=fake` 标注出来。
    """

    MODEL_TAG = "fake-copilot"

    async def chat(
        self,
        messages: list[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> ChatResponse:
        prompt = next(
            (message.content for message in reversed(messages) if message.role == MessageRole.USER),
            "",
        )
        draft = _draft_from_prompt(prompt)
        sentences = [part for part in _split_sentences(draft) if part]
        candidates: list[dict[str, str]] = []
        if sentences:
            candidates.append(
                {
                    "text": _join(sentences[:2]),
                    "rationale": "离线自测：取草稿开头两句（Fake 不做改写）",
                }
            )
            if len(sentences) > 2:
                candidates.append(
                    {
                        "text": _join(sentences[2:4]),
                        "rationale": "离线自测：取草稿后续句子（Fake 不做改写）",
                    }
                )
        if not candidates:
            candidates.append({"text": "离线自测", "rationale": "草稿为空，Fake 无法给出候选"})

        import json as _json

        text = _json.dumps(candidates[:MAX_CANDIDATES], ensure_ascii=False)
        return ChatResponse(
            text=text,
            usage=TokenUsage.of(len(prompt), len(text), latency_ms=0, model=self.MODEL_TAG),
        )


def _draft_from_prompt(prompt: str) -> str:
    marker = "草稿："
    index = prompt.find(marker)
    if index < 0:
        return ""
    body = prompt[index + len(marker) :]
    # 提示词的最后一节是格式要求，截掉它
    tail = body.find("\n\n请只输出")
    return (body[:tail] if tail >= 0 else body).strip()


def _split_sentences(text: str) -> list[str]:
    parts: list[str] = []
    buffer = ""
    for char in text:
        buffer += char
        if char in "。！？!?\n":
            parts.append(buffer.strip())
            buffer = ""
    if buffer.strip():
        parts.append(buffer.strip())
    return parts


def _join(sentences: list[str]) -> str:
    return "".join(sentences)[:200]


def _truncate(draft: str, limit: int) -> str:
    """超长草稿按「首 + 尾」保留：开头定调子，结尾是续写的接点。"""
    text = draft.strip()
    if len(text) <= limit:
        return text
    head = int(limit * _HEAD_RATIO)
    tail = limit - head
    omitted = len(text) - head - tail
    return f"{text[:head]}\n\n…（中间省略 {omitted} 字）…\n\n{text[-tail:]}"
