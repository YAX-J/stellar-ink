"""作者记忆的规则层（M9-1，`app/rag/memory.py`）。

这一层要守的是 roadmap M9 的三条验收：

1. **用户 A 的记忆不会被用户 B 召回** —— 用户隔离是硬约束，不参与排序；
2. **删除和禁用**都不再进提示词（`ACTIVE` 之外的一律过滤）；
3. **模型不能在没有证据时把推测写成永久用户事实** —— 候选的 `quote` 必须真的出现在上下文里。

另外两条工程口径：模型推测的可信度**封顶**（不该比用户确认过的更可信）、
敏感信息**宁可误伤**（记忆会反复进提示词）。
"""

from __future__ import annotations

from datetime import datetime, timedelta

from app.providers.models import ChatResponse, TokenUsage
from app.rag.memory import (
    MODEL_CONFIDENCE_CAP,
    MemoryCandidate,
    MemoryEvidence,
    MemoryRecord,
    MemorySource,
    MemoryStatus,
    collect_candidates,
    extract_candidates_async,
    filter_for_recall,
    plan_writes,
    sensitive_reason,
    similarity,
)

CONVERSATION = (
    "作者：我一直觉得句子短一点读起来才顺，长定语堆着我自己都读不下去。\n"
    "助手：那要不要把这个偏好记下来？\n"
    "作者：可以，另外这个「星笺」系列我决定不再写第二季了。\n"
    "作者：我的邮箱是 writer@example.com，有事发邮件。"
)

NOW = datetime(2026, 10, 2, 12, 0, 0)


def _payload(*items: dict[str, object]) -> dict[str, object]:
    return {"candidates": list(items)}


def _candidate(
    content: str,
    *,
    kind: str = "preference",
    quote: str = "",
    confidence: float = 0.6,
    source: str = MemorySource.MODEL_SUGGESTED,
) -> MemoryCandidate:
    return MemoryCandidate(
        memory_type=kind,
        content=content,
        evidence=(MemoryEvidence(kind="quote", ref=quote or content),),
        confidence=confidence,
        source=source,
    )


def _record(
    memory_id: int,
    content: str,
    *,
    user_id: int = 1,
    kind: str = "preference",
    confidence: float = 0.8,
    status: str = MemoryStatus.ACTIVE,
    evidence: tuple[MemoryEvidence, ...] = (),
) -> MemoryRecord:
    return MemoryRecord(
        memory_id=memory_id,
        user_id=user_id,
        memory_type=kind,
        content=content,
        confidence=confidence,
        status=status,
        evidence=evidence,
    )


# --------------------------------------------------------------- 候选校验


def test_candidate_without_observed_quote_is_dropped() -> None:
    """**M9 验收第三条**：没有出处的候选不许变成记忆。

    「作者喜欢在深夜写作」在这段对话里根本没出现过 —— 模型编的，必须丢。
    """
    payload = _payload(
        {
            "type": "preference",
            "content": "作者喜欢在深夜写作",
            "quote": "我一般都是凌晨三点开始写",
            "confidence": 0.9,
        },
        {
            "type": "preference",
            "content": "作者偏好短句",
            "quote": "我一直觉得句子短一点读起来才顺",
            "confidence": 0.6,
        },
    )

    candidates, dropped, _ = collect_candidates(payload, context=CONVERSATION)

    assert [item.content for item in candidates] == ["作者偏好短句"]
    assert dropped["noEvidence"] == 1, "编造的引用要单独计数，否则「抽得少」会被读成「模型不行」"


def test_user_stated_memory_still_needs_to_be_in_context() -> None:
    """用户自己说的话也算证据 —— 但**同样要在上下文里**。

    这条防止「界面上传一句话就能绕开证据校验」：绕开之后，写进库的东西
    与它声称的出处就对不上了。
    """
    payload = _payload(
        {
            "type": "decision",
            "content": "不再写第二季",
            "quote": "这个「星笺」系列我决定不再写第二季了",
            "confidence": 0.9,
        }
    )

    candidates, dropped, _ = collect_candidates(
        payload, context=CONVERSATION, source=MemorySource.USER_STATED
    )

    assert len(candidates) == 1
    assert candidates[0].source == MemorySource.USER_STATED
    assert not dropped

    fabricated, dropped2, _ = collect_candidates(
        _payload({"type": "decision", "content": "不再写第二季", "quote": "我不写了"}),
        context=CONVERSATION,
        source=MemorySource.USER_STATED,
    )
    assert fabricated == []
    assert dropped2["noEvidence"] == 1


def test_type_content_and_confidence_guards() -> None:
    payload = _payload(
        {"type": "emotion", "content": "作者今天心情不错", "quote": "句子短一点读起来才顺"},
        {"type": "preference", "content": "好", "quote": "句子短一点读起来才顺"},
        {"type": "preference", "content": "短" * 300, "quote": "句子短一点读起来才顺"},
        {
            "type": "preference",
            "content": "作者偏好短句",
            "quote": "句子短一点读起来才顺",
            "confidence": 0,
        },
        "我不是对象",
    )

    candidates, dropped, _ = collect_candidates(payload, context=CONVERSATION)

    assert candidates == []
    assert dropped["badType"] == 1
    assert dropped["tooShort"] == 1
    assert dropped["tooLong"] == 1
    assert dropped["lowConfidence"] == 1
    assert dropped["notAnObject"] == 1


def test_sensitive_information_is_never_stored() -> None:
    """敏感信息**宁可误伤**：记忆会被召回进提示词，等于反复送给模型。"""
    payload = _payload(
        {
            "type": "fact",
            "content": "作者的邮箱是 writer@example.com",
            "quote": "我的邮箱是 writer@example.com",
        },
        {
            "type": "fact",
            "content": "作者的手机号是 13800138000",
            "quote": "句子短一点读起来才顺",
        },
    )

    candidates, dropped, _ = collect_candidates(payload, context=CONVERSATION)

    assert candidates == []
    assert dropped["sensitive"] == 2


def test_sensitive_patterns_cover_the_usual_suspects() -> None:
    assert sensitive_reason("我的邮箱是 writer@example.com") == "email"
    assert sensitive_reason("打 13800138000 找我") == "phone"
    assert sensitive_reason("sk-abcdefghijklmnop") == "secret"
    assert sensitive_reason("密码：hunter2") == "password"
    assert sensitive_reason("110101199003074512") == "idCard"
    assert sensitive_reason("作者偏好短句") is None


def test_duplicate_candidates_in_one_payload_are_counted_once() -> None:
    item = {
        "type": "preference",
        "content": "作者偏好短句",
        "quote": "我一直觉得句子短一点读起来才顺",
    }

    candidates, dropped, _ = collect_candidates(_payload(item, dict(item)), context=CONVERSATION)

    assert len(candidates) == 1
    assert dropped["duplicateInPayload"] == 1


def test_model_confidence_is_capped() -> None:
    """模型推测的可信度**封顶**：它不该比用户确认过的更可信。"""
    payload = _payload(
        {
            "type": "preference",
            "content": "作者偏好短句",
            "quote": "我一直觉得句子短一点读起来才顺",
            "confidence": 0.99,
        }
    )

    by_model, _, _ = collect_candidates(payload, context=CONVERSATION)
    by_user, _, _ = collect_candidates(
        payload, context=CONVERSATION, source=MemorySource.USER_CONFIRMED
    )

    assert by_model[0].confidence == MODEL_CONFIDENCE_CAP
    assert by_user[0].confidence == 0.99, "用户确认过的按原值走"


class _StubChat:
    """离线桩：按顺序返回预设文本（不是 FakeProvider —— 这里只关心解析与校验）。"""

    def __init__(self, *texts: str) -> None:
        self._texts = list(texts)
        self.prompts: list[str] = []

    async def chat(self, messages: list[object], **_: object) -> ChatResponse:
        self.prompts.append(str(messages[0]))
        text = self._texts.pop(0) if self._texts else "{}"
        return ChatResponse(text=text, usage=TokenUsage(model="stub-model"))


async def test_extract_runs_the_model_and_validates_its_output() -> None:
    chat = _StubChat(
        '好的，这是结果：{"candidates": [{"type": "decision", "content": "不再写第二季", '
        '"quote": "这个「星笺」系列我决定不再写第二季了", "confidence": 0.8}]}'
    )

    outcome = await extract_candidates_async(CONVERSATION, chat)  # type: ignore[arg-type]

    assert [item.content for item in outcome.candidates] == ["不再写第二季"]
    assert outcome.usage_model == "stub-model"
    assert "preference/fact/decision" in chat.prompts[0], "类型白名单要写进提示词"
    assert "{example}" not in chat.prompts[0], "示例要被真的替换掉（E4 踩过模板字段名）"


async def test_extract_on_empty_conversation_does_not_call_the_model() -> None:
    chat = _StubChat()

    outcome = await extract_candidates_async("   ", chat)  # type: ignore[arg-type]

    assert outcome.candidates == []
    assert outcome.notes and "对话为空" in outcome.notes[0]
    assert chat.prompts == [], "没有内容就不该花钱调模型"


# --------------------------------------------------------------- 写入计划


def test_identical_candidate_is_a_duplicate_not_a_second_row() -> None:
    existing = [_record(1, "作者偏好短句")]
    plan = plan_writes(existing, [_candidate("作者偏好短句")])

    assert plan.to_add == []
    assert plan.duplicates[0][0] == 1
    assert not plan.conflicts


def test_duplicate_with_new_evidence_is_enriched_not_rewritten() -> None:
    """重复但带了**新出处**：补证据，内容不动（内容一动就成了「改写用户的记忆」）。"""
    existing = [
        _record(
            1,
            "作者偏好短句",
            evidence=(MemoryEvidence(kind="quote", ref="句子短一点"),),
        )
    ]
    incoming = _candidate("作者偏好短句", quote="我一直觉得句子短一点读起来才顺")

    plan = plan_writes(existing, [incoming])

    assert plan.duplicates[0][0] == 1
    assert plan.enriched[0][0] == 1
    assert plan.to_add == []


def test_similar_but_different_content_is_a_conflict() -> None:
    """**作者改过主意**是最该被看见的情况：措辞相近、结论不同 → 不自动覆盖。"""
    existing = [_record(1, "作者偏好把文章写长，一次讲透")]
    incoming = _candidate("作者偏好把文章写短，一次只讲一件事")

    plan = plan_writes(existing, [incoming])

    assert plan.to_add == []
    assert plan.conflicts[0][0].memory_id == 1
    assert plan.conflicts[0][1].content.startswith("作者偏好把文章写短")
    assert plan.notes and "不自动覆盖" in plan.notes[0]


def test_conflict_only_against_active_memories() -> None:
    """已禁用/已删除的记忆不再算冲突源：用户已经表达过态度，不该拦住新记忆。"""
    existing = [_record(1, "作者偏好把文章写长，一次讲透", status=MemoryStatus.DISABLED)]

    plan = plan_writes(existing, [_candidate("作者偏好把文章写短，一次只讲一件事")])

    assert len(plan.to_add) == 1
    assert plan.conflicts == []


def test_unrelated_candidate_against_different_type_is_new() -> None:
    existing = [_record(1, "作者偏好短句", kind="preference")]

    plan = plan_writes(existing, [_candidate("星笺系列不再写第二季", kind="decision")])

    assert len(plan.to_add) == 1
    assert plan.conflicts == []


def test_similarity_is_deterministic_and_explains_the_threshold() -> None:
    """相似度是 `max(Jaccard, 包含度)`：改主意那种「长句换几个字」靠包含度才抓得住。"""
    assert similarity("作者偏好短句", "作者偏好短句") == 1.0
    assert similarity("作者偏好短句", "今天天气不错") < 0.2
    # 标点不参与相似度：换个逗号不该像两条不同的记忆
    assert similarity("作者偏好短句，不爱长定语", "作者偏好短句不爱长定语") == 1.0
    # 实测：改主意那一对的 Jaccard 只有 0.45，但包含度 0.69 → 取大后才过阈值
    conflict_pair = similarity("作者偏好把文章写长，一次讲透", "作者偏好把文章写短，一次只讲一件事")
    assert conflict_pair >= 0.5
    # ⚠️ 已知弱区：短句子的改写（0.40）判不出来 —— 后果是多一条近乎重复的记忆，不是错误覆盖
    assert similarity("作者偏好短句", "作者喜欢写短句") < 0.5


# --------------------------------------------------------------- 召回过滤


def test_memory_of_another_user_is_never_recalled() -> None:
    """**M9 验收第一条**：用户 A 的记忆不会被 B 召回（硬隔离，不参与排序）。"""
    memories = [
        _record(1, "A 的偏好", user_id=1, confidence=0.95),
        _record(2, "B 的偏好", user_id=2, confidence=0.99),
    ]

    recalled = filter_for_recall(memories, user_id=1, now=NOW)

    assert [record.memory_id for record in recalled] == [1]


def test_disabled_and_deleted_memories_are_not_recalled() -> None:
    """**M9 验收第二条**：禁用与删除都不再进提示词。"""
    memories = [
        _record(1, "已禁用", status=MemoryStatus.DISABLED, confidence=0.99),
        _record(2, "已删除", status=MemoryStatus.DELETED, confidence=0.99),
        _record(3, "待确认", status=MemoryStatus.PENDING, confidence=0.99),
        _record(4, "生效中", status=MemoryStatus.ACTIVE, confidence=0.5),
    ]

    recalled = filter_for_recall(memories, user_id=1, now=NOW)

    assert [record.memory_id for record in recalled] == [4], "只有 ACTIVE 参与召回"


def test_recall_filters_types_confidence_and_expiry() -> None:
    memories = [
        _record(1, "偏好", kind="preference", confidence=0.9),
        _record(2, "事实", kind="fact", confidence=0.9),
        _record(3, "低可信", kind="preference", confidence=0.2),
        _record(4, "过期了", kind="preference", confidence=0.9),
    ]
    expiry = {4: NOW - timedelta(days=1)}

    recalled = filter_for_recall(
        memories,
        user_id=1,
        now=NOW,
        types=["preference"],
        min_confidence=0.5,
        expires_at=expiry,
    )

    assert [record.memory_id for record in recalled] == [1]

    # 没过期的那条仍然在（区分「没设过期」与「已过期」）
    still_valid = filter_for_recall(
        memories, user_id=1, now=NOW, expires_at={4: NOW + timedelta(days=1)}
    )
    assert 4 in [record.memory_id for record in still_valid]


def test_recall_order_is_confidence_then_recency_and_capped() -> None:
    memories = [
        _record(1, "低", confidence=0.3),
        _record(2, "高", confidence=0.9),
        _record(3, "高但更早", confidence=0.9),
    ]

    recalled = filter_for_recall(memories, user_id=1, now=NOW, limit=2)

    assert [record.memory_id for record in recalled] == [3, 2], "同可信度时新的在前；结果确定可复现"
