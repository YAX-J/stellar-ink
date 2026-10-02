"""降到备用模型（M6，`app/providers/fallback.py`）。

roadmap M6 第 6 条：「云端模型失败时，可按策略降级到本地模型；**高风险任务不静默更换模型**」。
这份用例就是那半句话的落地检查 —— 三件事：

1. **「静默」被禁止**：降级后 `usage.model` 是**备用模型**（上游的真话），
   并留下一条 `FallbackNote`。把主模型名字留着，等于让「降级了」永远查不出来。
2. **「按策略」是真的有开关**：可降级场景（问答/建议/Agent）才降；
   **会被存进库或拿来比较**的场景（Wiki 抽取、评测、记忆抽取）宁可失败 ——
   换模型会让同一次构建里混进两种笔迹，而这两种笔迹在库里长得一模一样：
   失败看得见，混进库看不见。
3. **只对可重试错误降级**：配置类错误（401/密钥错、模型名写错）不降级，
   否则「配置错了」会被掩盖成「能用」；额度用尽也不降级（它是「今天别试了」的明确结论）。
"""

from __future__ import annotations

import pytest

from app.providers.errors import (
    ProviderAuthError,
    ProviderQuotaExhaustedError,
    ProviderRateLimitError,
    ProviderUnavailableError,
)
from app.providers.fallback import FALLBACK_ALLOWED_SCENES, FallbackChatModel
from app.providers.models import ChatMessage, ChatResponse, MessageRole, TokenUsage


class _StubChat:
    """按脚本回答：要么给一个响应，要么抛一个错误。"""

    def __init__(self, *, model: str, error: Exception | None = None, text: str = "答") -> None:
        self.model = model
        self.error = error
        self.text = text
        self.calls = 0

    async def chat(self, messages: list[object], **_: object) -> ChatResponse:
        del messages
        self.calls += 1
        if self.error is not None:
            raise self.error
        return ChatResponse(
            text=self.text,
            usage=TokenUsage(model=self.model, prompt_tokens=1, completion_tokens=1),
        )


def _pair(scene: str, error: Exception | None) -> tuple[FallbackChatModel, _StubChat, _StubChat]:
    primary = _StubChat(model="cloud-chat", error=error, text="云端的答案")
    fallback = _StubChat(model="local-qwen", text="本地的答案")
    model = FallbackChatModel(
        primary=primary,  # type: ignore[arg-type]
        fallback=fallback,  # type: ignore[arg-type]
        scene=scene,
        primary_name="deepseek-chat",
        fallback_name="local-qwen",
    )
    return model, primary, fallback


async def test_success_does_not_touch_the_fallback() -> None:
    model, primary, fallback = _pair("qa", None)

    response = await model.chat([ChatMessage(MessageRole.USER, "问")])

    assert response.text == "云端的答案"
    assert fallback.calls == 0, "主模型好好的就不该动备用模型"
    assert model.notes == [], "没降级就没有记录"


async def test_transient_failure_falls_back_and_says_so() -> None:
    """降级成功后，回答要**标明是备用模型答的**（`usage.model` + 一条记录）。"""
    model, primary, fallback = _pair("qa", ProviderUnavailableError("模型服务返回 503"))

    response = await model.chat([ChatMessage(MessageRole.USER, "问")])

    assert primary.calls == 1 and fallback.calls == 1
    assert response.text == "本地的答案"
    assert response.usage.model == "local-qwen", "usage.model 必须是真话（谁答的）"
    assert len(model.notes) == 1
    note = model.notes[0]
    assert note.scene == "qa"
    assert note.primary == "deepseek-chat" and note.fallback == "local-qwen"
    assert "503" in note.reason, "记录里要写清为什么降级"
    assert note.answered_by == "local-qwen"


@pytest.mark.parametrize("scene", ["wiki", "eval", "memory", "index"])
async def test_high_risk_scenes_never_switch_models(scene: str) -> None:
    """会被**存进库**或**拿来比较**的场景：宁可失败，也不换模型。

    换模型的后果是「同一次构建里混进两种笔迹」，而这两种笔迹在库里长得一模一样。
    """
    model, primary, fallback = _pair(scene, ProviderUnavailableError("模型服务返回 503"))

    with pytest.raises(ProviderUnavailableError):
        await model.chat([ChatMessage(MessageRole.USER, "问")])

    assert primary.calls == 1
    assert fallback.calls == 0, f"{scene} 场景不该降级"
    assert model.notes == []
    assert scene not in FALLBACK_ALLOWED_SCENES


@pytest.mark.parametrize(
    "error",
    [
        ProviderAuthError("模型密钥无效或无权限，请检查面板里的 API Key"),
        ProviderQuotaExhaustedError("免费档今日 50 次已用尽，次日零点重置"),
    ],
)
async def test_configuration_and_quota_errors_do_not_fall_back(error: Exception) -> None:
    """配置错要人去改配置、额度用尽要人等——两者都不该被一个「能用」的备用模型掩盖。"""
    model, _primary, fallback = _pair("qa", error)

    with pytest.raises(type(error)):
        await model.chat([ChatMessage(MessageRole.USER, "问")])

    assert fallback.calls == 0


async def test_transient_rate_limit_does_fall_back() -> None:
    """瞬时限流（区别于额度用尽）要降级：它是「上游在抖」，等一会儿就好了。"""
    model, _primary, fallback = _pair("agent", ProviderRateLimitError("请求过于频繁"))

    response = await model.chat([ChatMessage(MessageRole.USER, "问")])

    assert fallback.calls == 1
    assert response.usage.model == "local-qwen"


async def test_fallback_failure_surfaces_the_fallback_error() -> None:
    """备用模型也失败时，抛**它**的错误 —— 那才是用户实际遇到的东西。

    抛主模型的错误会让人去查一个已经绕开的问题（甚至可能已经恢复了）。
    """
    primary = _StubChat(model="cloud-chat", error=ProviderUnavailableError("云端 503"))
    fallback = _StubChat(model="local-qwen", error=ProviderAuthError("本地服务密钥不对"))
    model = FallbackChatModel(
        primary=primary,  # type: ignore[arg-type]
        fallback=fallback,  # type: ignore[arg-type]
        scene="qa",
        primary_name="deepseek-chat",
        fallback_name="local-qwen",
    )

    with pytest.raises(ProviderAuthError, match="本地服务密钥不对"):
        await model.chat([ChatMessage(MessageRole.USER, "问")])

    assert model.notes == [], "降级没成功就不该留下「已降级」的记录"


async def test_notes_accumulate_for_observability() -> None:
    """「降级过」这件事必须有个地方能看见，否则它等于没发生过。"""
    model, _primary, fallback = _pair("qa", ProviderUnavailableError("503"))
    del fallback

    await model.chat([ChatMessage(MessageRole.USER, "问一")])
    await model.chat([ChatMessage(MessageRole.USER, "问二")])

    assert len(model.notes) == 2
    assert {note.scene for note in model.notes} == {"qa"}
