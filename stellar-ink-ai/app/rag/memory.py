"""作者记忆：模型只产出**候选**，能不能变成长期记忆由规则与用户确认决定（M9-1）。

为什么不能「让模型自己决定记什么」：记忆一旦落库就带着「这是用户的事实」的语气，
而模型最容易把**一次对话里的推测**写成永久事实（roadmap M9 验收第三条就是这个）。
所以这里守住三件事：

1. **每条候选都要有被观察到的证据** —— `quote` 必须真的出现在这次上下文里
   （规范化空白后比对），用户自己说的话也算证据（它就在上下文里）。
   没证据的直接丢弃并按原因计数，与 E4 的主张抽取同一套纪律：
   **宁可少记，也不记没有出处的东西**。
2. **候选不等于写入**：`plan_writes` 只输出「新增 / 重复 / 冲突」三份清单，
   冲突**不自动覆盖**（同一个类型、措辞很近但结论不同 → 交给人决定）。
3. **敏感信息不进记忆**：手机号、邮箱、证件号、密钥样式一律丢弃。
   记忆会被召回进提示词，等于把它反复送进模型 —— 这不是「顺手存一下」的地方。

召回侧只做确定性过滤（用户 / 类型 / 时间 / 可信度），不做语义打分：
`filter_for_recall` 的**第一条硬约束是用户隔离**（用户 A 的记忆绝不能被 B 召回），
这条不依赖任何相关性算法，也不能被后面的排序推翻。
"""

from __future__ import annotations

import json
import re
import unicodedata
from collections import Counter
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from datetime import datetime
from enum import StrEnum
from typing import Any

from app.providers.base import ChatModel
from app.providers.models import ChatMessage, MessageRole

#: 能进记忆的类型白名单。**刻意只有三类**：偏好 / 事实 / 决定。
#: 少而稳的好处是前端只需要三种呈现方式；加类型要同时改这里、SQL 注释与前端标签。
MEMORY_TYPES = ("preference", "fact", "decision")

#: 一条记忆的正文长度上下限。太短没有信息量（「好」「嗯」），太长说明模型把整段对话抄了进来。
MIN_CONTENT_LENGTH = 4
MAX_CONTENT_LENGTH = 200

#: 默认可信度：模型推测的上限。用户确认过的记忆可以更高（见 `MemorySource`）。
MODEL_CONFIDENCE_CAP = 0.7

#: 判定「措辞相近」的字符二元组相似度阈值。
#:
#: 中文没有词边界，二元组是不引分词库的确定性近似。**只用 Jaccard 不够**：
#: 它惩罚长度差，而「作者改了主意」往往就是在一句较长的话里换了几个字 ——
#: 实测「作者偏好把文章写长，一次讲透」vs「…写短，一次只讲一件事」
#: Jaccard 只有 0.45（够不到 0.5），但包含度 0.69。
#: 所以取 `max(Jaccard, 包含度)`：包含度处理「一条是另一条的改写」，Jaccard 处理「长度相当」。
#: 0.5 之下、0.2 之上的区间（例如「作者偏好短句」vs「作者喜欢写短句」= 0.40）
#: **判不出来** —— 后果是多出一条近乎重复的记忆，而不是错误覆盖，这是刻意选的方向。
CONFLICT_SIMILARITY = 0.5

#: 敏感信息样式。**宁可误伤**：记忆会进提示词，漏一个比多丢一条贵得多。
_SENSITIVE_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("phone", re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")),
    ("email", re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")),
    ("idCard", re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")),
    ("bankCard", re.compile(r"(?<!\d)\d{16,19}(?!\d)")),
    ("secret", re.compile(r"\b(?:sk|ak|ghp|xoxb)-[\w-]{8,}", re.IGNORECASE)),
    ("password", re.compile(r"(?:密码|口令|password|passwd)\s*[:：=]?\s*\S{4,}", re.IGNORECASE)),
)

PROMPT = """你在帮一位博客作者整理**长期写作记忆**的候选。只输出 JSON，不要解释。

从下面这段对话里，抽出**值得长期记住**的候选（类型限定 {types}）：
- preference：作者稳定的偏好（喜欢什么写法、讨厌什么做法）
- fact：关于作者本人或作品的**可核对事实**（不是你的推测）
- decision：作者明确做过的决定（例如「这个系列不再写第二季」）

每条候选必须给出 `quote`：**从下面这段对话里逐字复制**的一句话，作为它的出处。
找不到出处就不要写这条 —— 宁可不记，也不要记没有出处的东西。
不要输出手机号、邮箱、证件号、密钥等敏感信息。

对话：
{conversation}

JSON 示例：
{example}
"""

#: 示例单独放常量：直接写进模板会被 `str.format` 当字段名（E4 踩过，报出无关的 KeyError）
CANDIDATE_JSON_EXAMPLE = """{"candidates": [
  {"type": "preference", "content": "作者偏好短句，不爱用长定语",
   "quote": "我一直觉得句子短一点读起来才顺", "confidence": 0.6}
]}"""


class MemoryType(StrEnum):
    """记忆类型（与 `MEMORY_TYPES` 同源）。"""

    PREFERENCE = "preference"
    FACT = "fact"
    DECISION = "decision"


class MemorySource(StrEnum):
    """这条记忆是怎么来的 —— 决定它的初始可信度与能不能直接生效。"""

    #: 模型从对话里推测出来的：**只进候选**，要规则或用户点头才能持久化
    MODEL_SUGGESTED = "model_suggested"
    #: 用户自己说的（原文就在上下文里）
    USER_STATED = "user_stated"
    #: 用户在界面上确认过：可信度最高
    USER_CONFIRMED = "user_confirmed"


class MemoryStatus(StrEnum):
    """记忆状态。`DISABLED` 与 `DELETED` **都不参与召回**（前者可恢复、后者等清理）。"""

    PENDING = "pending"
    ACTIVE = "active"
    DISABLED = "disabled"
    DELETED = "deleted"


@dataclass(frozen=True, slots=True)
class MemoryEvidence:
    """一条证据：要么回到某段原文，要么是用户自己确认过。"""

    kind: str
    ref: str
    post_id: int | None = None


@dataclass(frozen=True, slots=True)
class MemoryCandidate:
    """模型/用户产出的一条候选（**还没落库**）。"""

    memory_type: str
    content: str
    evidence: tuple[MemoryEvidence, ...]
    confidence: float
    source: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": self.memory_type,
            "content": self.content,
            "confidence": round(self.confidence, 4),
            "source": self.source,
            "evidence": [
                {"kind": item.kind, "ref": item.ref, "postId": item.post_id}
                for item in self.evidence
            ],
        }


@dataclass(slots=True)
class ExtractionOutcome:
    """一次抽取的结果：候选 + **按原因分类的丢弃计数**。"""

    candidates: list[MemoryCandidate] = field(default_factory=list)
    dropped: Counter[str] = field(default_factory=Counter)
    notes: list[str] = field(default_factory=list)
    usage_model: str | None = None


def normalize_memory_text(text: str) -> str:
    """归一化：全角转半角、去空白、大小写折叠。

    与实体归一化同一套动作（`entities.normalize_entity` 之外再保留空白后的形态），
    目的是让「同一条记忆的两种写法」被判成重复，而不是两条。
    """
    folded = unicodedata.normalize("NFKC", text).casefold()
    return re.sub(r"\s+", "", folded)


def sensitive_reason(text: str) -> str | None:
    """这条文本命中哪类敏感信息（没命中返回 `None`）。"""
    for reason, pattern in _SENSITIVE_PATTERNS:
        if pattern.search(text):
            return reason
    return None


def _observed(quote: str, context: str) -> bool:
    """引用是否**真的出现在上下文里**（规范化空白后比对）。

    与 E4 主张同一条判据：只在「逐字出现」时才算证据，
    这样「模型编了一句作者没说过的话」会被直接挡在门外。
    """
    if len(quote.strip()) < MIN_CONTENT_LENGTH:
        return False
    return normalize_memory_text(quote) in normalize_memory_text(context)


def collect_candidates(
    payload: dict[str, Any] | None,
    *,
    context: str,
    source: str = MemorySource.MODEL_SUGGESTED,
) -> tuple[list[MemoryCandidate], Counter[str], list[str]]:
    """校验模型输出，产出**通过校验**的候选与丢弃原因计数。

    丢弃原因（都会计数，避免「抽出来很少」被读成「模型不行」）：
    `badType`（类型不在白名单）/ `tooShort` / `tooLong` / `noEvidence`（无出处或出处编造）/
    `sensitive`（命中敏感样式）/ `lowConfidence`（可信度为 0）。
    """
    candidates: list[MemoryCandidate] = []
    dropped: Counter[str] = Counter()
    if not payload:
        return candidates, dropped, []

    items = payload.get("candidates")
    if not isinstance(items, list):
        return candidates, dropped, ["模型没有返回 candidates 列表。"]

    seen: set[tuple[str, str]] = set()
    for item in items:
        if not isinstance(item, dict):
            dropped["notAnObject"] += 1
            continue
        memory_type = str(item.get("type", ""))
        if memory_type not in MEMORY_TYPES:
            dropped["badType"] += 1
            continue
        content = str(item.get("content", "")).strip()
        if len(content) < MIN_CONTENT_LENGTH:
            dropped["tooShort"] += 1
            continue
        if len(content) > MAX_CONTENT_LENGTH:
            dropped["tooLong"] += 1
            continue
        quote = str(item.get("quote", "")).strip()
        if not _observed(quote, context):
            dropped["noEvidence"] += 1
            continue
        if sensitive_reason(content) or sensitive_reason(quote):
            dropped["sensitive"] += 1
            continue
        try:
            raw_confidence = float(item.get("confidence", 0.5))
        except (TypeError, ValueError):
            raw_confidence = 0.5
        confidence = min(max(raw_confidence, 0.0), 1.0)
        if confidence <= 0.0:
            dropped["lowConfidence"] += 1
            continue
        # 模型推测的**封顶**：它不该比用户确认过的东西更可信
        if source == MemorySource.MODEL_SUGGESTED:
            confidence = min(confidence, MODEL_CONFIDENCE_CAP)

        key = (memory_type, normalize_memory_text(content))
        if key in seen:
            dropped["duplicateInPayload"] += 1
            continue
        seen.add(key)
        candidates.append(
            MemoryCandidate(
                memory_type=memory_type,
                content=content,
                evidence=(MemoryEvidence(kind="quote", ref=quote),),
                confidence=confidence,
                source=source,
            )
        )
    dropped = Counter({key: value for key, value in dropped.items() if value})
    return candidates, dropped, []


async def extract_candidates_async(
    conversation: str,
    chat: ChatModel,
    *,
    max_candidates: int = 5,
    source: str = MemorySource.MODEL_SUGGESTED,
) -> ExtractionOutcome:
    """让模型从一段对话里产出候选，并**立刻**用规则校验。

    ⚠️ 上下文长度由调用方限制（当前对话只是工作记忆，见 roadmap M9 第 1 条）：
    这里不截断 —— 悄悄丢掉半段对话会让「为什么没记住」变得无从解释。
    """
    if not conversation.strip():
        return ExtractionOutcome(notes=["对话为空，没有可抽取的内容。"])
    response = await chat.chat(
        [ChatMessage(MessageRole.USER, _prompt(conversation, max_candidates))]
    )
    payload = _parse_payload(response.text)
    candidates, dropped, notes = collect_candidates(payload, context=conversation, source=source)
    return ExtractionOutcome(
        candidates=candidates,
        dropped=dropped,
        notes=notes,
        usage_model=response.usage.model or None,
    )


def _prompt(conversation: str, max_candidates: int) -> str:
    return (
        PROMPT.format(
            types="/".join(MEMORY_TYPES),
            conversation=conversation,
            example=CANDIDATE_JSON_EXAMPLE,
        )
        + f"\n最多 {max_candidates} 条。"
    )


def _parse_payload(text: str) -> dict[str, Any] | None:
    """从模型输出里抠出 JSON 对象（模型常带前后解释文字）。"""
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        payload = json.loads(text[start : end + 1])
    except ValueError:
        return None
    return payload if isinstance(payload, dict) else None


# ------------------------------------------------------------------ 写入计划


@dataclass(frozen=True, slots=True)
class MemoryRecord:
    """库里已有的一条记忆（`plan_writes` 的输入）。"""

    memory_id: int
    user_id: int
    memory_type: str
    content: str
    confidence: float
    status: str
    evidence: tuple[MemoryEvidence, ...] = ()


@dataclass(slots=True)
class MemoryPlan:
    """写入计划：**分三份清单**，让「要不要写」这件事看得见。"""

    to_add: list[MemoryCandidate] = field(default_factory=list)
    duplicates: list[tuple[int, MemoryCandidate]] = field(default_factory=list)
    conflicts: list[tuple[MemoryRecord, MemoryCandidate]] = field(default_factory=list)
    #: 重复但带来了**新证据**：建议把证据补上（内容不变）
    enriched: list[tuple[int, MemoryCandidate]] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


def shingles(text: str) -> set[str]:
    """字符二元组集合：中文没有词边界的条件下，这是不引分词库的确定性相似度基础。

    **标点与符号先丢掉**：它们成不了二元组的语义单位，留着只会让「同一句话换个逗号」
    看起来像两条不同的记忆（归一化仍保留标点，那只影响存储与展示，不影响相似度）。
    """
    cleaned = "".join(
        character
        for character in normalize_memory_text(text)
        if not unicodedata.category(character).startswith(("P", "S", "Z"))
    )
    if len(cleaned) < 2:
        return {cleaned} if cleaned else set()
    return {cleaned[index : index + 2] for index in range(len(cleaned) - 1)}


def similarity(left: str, right: str) -> float:
    """两条记忆正文的相似度（0~1）：`max(Jaccard, 包含度)`。

    为什么要两者取大：Jaccard 惩罚长度差，而「改了主意」常常是在一句更长的话里换几个字；
    包含度（交集 / 较短者）能抓住「一条是另一条的改写」，但对「都很长且各说各的」不够严，
    所以两者取大 —— 任一角度像，就交给规则或用户去看。
    """
    a, b = shingles(left), shingles(right)
    if not a or not b:
        return 0.0
    intersection = len(a & b)
    jaccard = intersection / len(a | b)
    containment = intersection / min(len(a), len(b))
    return max(jaccard, containment)


def plan_writes(
    existing: Sequence[MemoryRecord],
    incoming: Iterable[MemoryCandidate],
    *,
    conflict_similarity: float = CONFLICT_SIMILARITY,
) -> MemoryPlan:
    """把候选分成「新增 / 重复 / 冲突」。

    三条判定（顺序不能换）：

    1. **归一化后完全相同** → 重复；若候选带来了库里没有的证据 → 进 `enriched`（补证据，不改内容）；
    2. **同类型且措辞相近（相似度 ≥ 阈值）但正文不同** → **冲突**：
       这可能是同义改写，也可能是作者改了主意（「以后写长文」→「以后只写短篇」）。
       两者**都不该自动覆盖** —— 前者会多出一行重复，后者会把「他改过主意」这段历史抹掉，
       所以只报冲突，由规则或用户决定；
    3. 其余 → 新增。
    """
    plan = MemoryPlan()
    remaining: list[MemoryRecord] = list(existing)

    for candidate in incoming:
        normalized = normalize_memory_text(candidate.content)
        identical = next(
            (
                record
                for record in remaining
                if record.memory_type == candidate.memory_type
                and normalize_memory_text(record.content) == normalized
                and record.status != MemoryStatus.DELETED
            ),
            None,
        )
        if identical is not None:
            plan.duplicates.append((identical.memory_id, candidate))
            known = {(item.kind, normalize_memory_text(item.ref)) for item in identical.evidence}
            fresh = [
                item
                for item in candidate.evidence
                if (item.kind, normalize_memory_text(item.ref)) not in known
            ]
            if fresh:
                plan.enriched.append((identical.memory_id, candidate))
            continue

        conflict = next(
            (
                record
                for record in remaining
                if record.memory_type == candidate.memory_type
                and record.status == MemoryStatus.ACTIVE
                and similarity(record.content, candidate.content) >= conflict_similarity
            ),
            None,
        )
        if conflict is not None:
            plan.conflicts.append((conflict, candidate))
            continue

        plan.to_add.append(candidate)

    if plan.conflicts:
        plan.notes.append(
            f"{len(plan.conflicts)} 条候选与已有记忆措辞相近但结论不同 —— "
            "按「作者可能改过主意」处理：**不自动覆盖**，交给规则或用户确认。"
        )
    if plan.duplicates:
        plan.notes.append(f"{len(plan.duplicates)} 条候选与已有记忆重复，未重复写入。")
    return plan


# ------------------------------------------------------------------ 召回过滤


def filter_for_recall(
    memories: Sequence[MemoryRecord],
    *,
    user_id: int,
    now: datetime,
    types: Sequence[str] | None = None,
    min_confidence: float = 0.0,
    limit: int = 10,
    expires_at: dict[int, datetime | None] | None = None,
) -> list[MemoryRecord]:
    """召回候选：**先硬隔离用户**，再按类型 / 可信度 / 有效期过滤，最后排序截断。

    用户隔离放在第一步且不参与排序：任何相关性算法都不能把别人的记忆捞回来
    （roadmap M9 验收第一条）。`status` 只认 `ACTIVE` —— `DISABLED` 是用户主动关掉的，
    `DELETED` 等清理，两者都不该再进提示词。

    `expires_at` 单独传（而不是塞进 `MemoryRecord`）：过期时间在库里是**可空列**，
    而这里要区分「没设过期」与「已过期」，用一个显式映射比给记录加可空字段更不容易读错。
    """
    allowed = set(types) if types else None
    expiry = expires_at or {}
    kept: list[MemoryRecord] = []
    for record in memories:
        if record.user_id != user_id:
            continue
        if record.status != MemoryStatus.ACTIVE:
            continue
        if allowed is not None and record.memory_type not in allowed:
            continue
        if record.confidence < min_confidence:
            continue
        deadline = expiry.get(record.memory_id)
        if deadline is not None and deadline <= now:
            continue
        kept.append(record)
    # 可信度优先、其次 id 倒序（新的在前）；id 参与排序让结果**确定可复现**
    kept.sort(key=lambda record: (-record.confidence, -record.memory_id))
    return kept[:limit]
