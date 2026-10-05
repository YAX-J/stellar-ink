"""`answerer` 司职：**无工具、纯生成**。

今天 RAG 那条路是「检索前置 + 一次生成」。本司职只负责**生成那一步**：
摘录由调用方给（今天的形态里由 `app/api/v1/agent.py` 从**同一条** `AGENT_RETRIEVAL`
管线取，与问答、searcher 共用一份检索），这里只做「把摘录与问题拼成一次模型调用，
并如实翻译结束原因」。

为什么不让它自己检索：那会让「检索」出现第二份实现，而本仓库最贵的一课正是
**两条链路各写一遍检索**（评测与线上分叉，见 development-workflow「只有一条检索编排」）。
所以这里刻意接受 `excerpts: Sequence[Excerpt]` 而不是一条 `RetrievalPipeline`。

`citations` 也不在这里组装：引用的片段与分数必须来自**观察到的检索结果**，
而本模块没有检索 —— 返回一个空引用列表是诚实的，编一份出来才是问题。

结束原因复用 `app.rag.qa.model_outcome`：它已经分清了「正常说完 / 被 token 预算截断 /
模型拒答」三种情况，而这三者在前端是三种不同的显示。各写一遍必然分叉 ——
历史上其中一处就把「截断」显示成了「拒答」。
"""

from __future__ import annotations

import time
from collections.abc import Sequence
from dataclasses import dataclass, field

from app.agents.profile import AgentProfile
from app.providers.base import ChatModel, model_tag_of
from app.providers.models import ChatMessage, MessageRole
from app.rag.agent import AgentSettings
from app.rag.qa import DEFAULT_REFUSAL, TRUNCATED_MESSAGE, model_outcome
from app.schemas.common import DoneReason


@dataclass(frozen=True, slots=True)
class Excerpt:
    """一段送进模型的摘录：编号即提示词里的 `[n]`。"""

    number: int
    title: str
    text: str


@dataclass(frozen=True, slots=True)
class AnswerRun:
    """一次纯生成的结果。

    `evidence_sufficient` 与 `done_reason` 是**两个维度**（照 `qa.model_outcome` 的口径）：
    「有没有依据」和「答完没有」可以同时是「有依据 + 被截断」。
    """

    answer: str
    done_reason: DoneReason
    evidence_sufficient: bool
    latency_ms: int = 0
    usage_model: str | None = None


def refusal_run(latency_ms: int = 0, *, refusal_message: str = DEFAULT_REFUSAL) -> AnswerRun:
    """没有摘录时的收尾：**一次模型调用都不花**。

    与问答同一条口径（`app/rag/qa.py`）：检索层说「没有候选」就是没有依据，
    这时叫模型只会得到一个看起来像答案的编造。
    """
    return AnswerRun(
        answer=refusal_message,
        done_reason=DoneReason.REFUSED,
        evidence_sufficient=False,
        latency_ms=max(0, latency_ms),
    )


def truncated_run(latency_ms: int = 0) -> AnswerRun:
    """有摘录但模型在给出正文前就烧完 token 预算。

    **调用方要显式用它的场景**：`Answerer.generate` 已经会这样收尾（`finish_reason=length`
    且没有正文），所以这个函数只用于「调用方自己在更早一步发现预算没了」的情况 ——
    例如提示词本身已经超长，压根没必要发这一次请求。
    """
    return AnswerRun(
        answer=TRUNCATED_MESSAGE,
        done_reason=DoneReason.LENGTH,
        evidence_sufficient=True,
        latency_ms=max(0, latency_ms),
    )


def build_user_prompt(question: str, excerpts: Sequence[Excerpt] = ()) -> str:
    """拼用户消息：先问题、后摘录，最后一句明确的作答要求。

    没有摘录时**不编造「站内摘录」段**：那等于告诉模型「下面是证据」而下面是空的。
    """
    lines = [f"问题：{question}", ""]
    if excerpts:
        lines.append("站内摘录：")
        for excerpt in excerpts:
            lines.append(f"[{excerpt.number}]《{excerpt.title}》：{excerpt.text}")
        lines.append("")
        lines.append("请仅依据以上摘录回答，并用 [编号] 标注来源。")
    else:
        lines.append("（这次没有站内摘录，请如实说明没有依据，不要编造内容。）")
    return "\n".join(lines)


@dataclass(slots=True)
class Answerer:
    """一次生成的薄封装。无状态（可并发复用），模型由调用方注入。"""

    chat: ChatModel
    settings: AgentSettings = field(default_factory=AgentSettings)

    async def generate(
        self,
        system_prompt: str,
        question: str,
        excerpts: Sequence[Excerpt] = (),
        *,
        refusal_message: str = DEFAULT_REFUSAL,
    ) -> AnswerRun:
        text = str(question or "").strip()
        if not text:
            raise ValueError("问题不能为空")
        started = time.perf_counter()
        response = await self.chat.chat(
            [
                ChatMessage(role=MessageRole.SYSTEM, content=system_prompt),
                ChatMessage(role=MessageRole.USER, content=build_user_prompt(text, excerpts)),
            ],
            temperature=self.settings.temperature,
            max_tokens=self.settings.max_tokens,
        )
        outcome = model_outcome(
            response.text, response.finish_reason, refusal_message=refusal_message
        )
        return AnswerRun(
            answer=outcome.text,
            done_reason=outcome.done_reason,
            evidence_sufficient=outcome.evidence_sufficient,
            latency_ms=max(0, int((time.perf_counter() - started) * 1000)),
            usage_model=model_tag_of(self.chat),
        )


#: 司职声明（`app/agents/registry.py` 从这里取）。提示词刻意保持最小，
#: 与 `qa.SYSTEM_PROMPT` 同一条口径：只要求「仅依据摘录」，不教文风 —— 文风属于人设。
SYSTEM_PROMPT = (
    "你是「星笺」的生成助手。只依据下面给出的摘录作答，"
    "不要使用摘录之外的知识，也不要编造细节。"
    "每条结论后面用 [1] [2] 这样的编号标注它来自哪段摘录。"
    "如果摘录不足以回答，就直说「文章里没有找到依据」，不要勉强作答。"
)

#: 预算：**一次生成、没有工具**。
#: `max_steps=1` 不是「留点余地」而是字面意思 —— 这个司职只有一步。
#: `max_tool_calls=1` 只是为了满足 `AgentSettings` 的下界（它不接受 0）；
#: 本司职的工具集是空的，这个数字在任何路径上都不会被读到。
SETTINGS = AgentSettings(
    max_steps=1,
    max_tool_calls=1,
    max_observation_chars=4_000,
)

PROFILE = AgentProfile(
    name="answerer",
    title="生成助手",
    system_prompt=SYSTEM_PROMPT,
    tool_names=(),
    settings=SETTINGS,
    scene="agent",
)
