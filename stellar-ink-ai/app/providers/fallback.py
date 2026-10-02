"""降级到备用模型（M6）：**只有明确允许时才换，且一定说出来**。

roadmap M6 第 6 条：「云端模型失败时，可按策略降级到本地模型；**高风险任务不静默更换模型**」。

这句话拆成两条实现约束：

1. **「静默」是禁止的**。换模型会改变输出风格与质量，用户/调用方必须能知道
   「这次不是主模型答的」——所以降级成功后，`usage.model` 记的是**备用模型**
   （上游 Usage 本来就是真话），并且这里额外记一条 `FallbackNote`。
   把主模型名字留着，等于让「降级了」这件事永远查不出来。
2. **「按策略」是真的有开关**，不是一个恒定行为。区分两类任务：
   * **可降级**（有人看着结果、错了能发现）：问答、Copilot 建议、Agent ——
     云端 5xx/超时时，用本地模型答一个，比让作者干等强。
   * **不可降级**（结果会被**存进库**或**拿来比较**）：Wiki 抽取、评测、记忆抽取 ——
     换模型会让同一次构建里混进两种"笔迹"，而这两种笔迹在库里长得一模一样。
     这类任务宁可失败：失败看得见，混进库看不见。

另外两条：
* **只对可重试类错误降级**（5xx/超时/连不上/瞬时限流）。配置类错误（401、模型名写错）
  **不降级** —— 那是要人去改配置的，换成备用模型只会把「配置错了」掩盖成「能用」。
* 备用模型**自己也失败**时，抛**备用模型**的错误：那才是用户实际遇到的东西。
  把主模型的错误再抛出来，会让人去查一个已经绕开的问题。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.providers.base import ChatModel
from app.providers.errors import ProviderError, ProviderQuotaExhaustedError
from app.providers.models import ChatResponse

#: 允许降级的场景白名单（**默认拒绝**：新场景要显式加进来）。
#:
#: 为什么默认拒绝而不是默认允许：降级的代价是「结果里混进另一种笔迹」，
#: 而白名单漏一个场景的表现是「明明能降级却失败了」——看得见；
#: 反过来（默认允许）漏一个的表现是「库里混进了别的模型的输出」——看不见。
FALLBACK_ALLOWED_SCENES = frozenset({"qa", "writing", "agent", "chat"})


@dataclass(slots=True)
class FallbackNote:
    """一次降级的记录（谁换成了谁、为什么）。"""

    scene: str
    primary: str
    fallback: str
    reason: str
    #: 降级后那次调用用的模型（从上游 usage 读到的真话）
    answered_by: str | None = None


@dataclass(slots=True)
class FallbackChatModel:
    """主模型失败时改用备用模型（`ChatModel` 协议）。

    `notes` 会累积这次进程里的降级记录，供观测出口/日志查看 —— 「降级过」这件事
    必须有个地方能看见，否则它等于没发生过。
    """

    primary: ChatModel
    fallback: ChatModel
    scene: str
    primary_name: str = "primary"
    fallback_name: str = "fallback"
    notes: list[FallbackNote] = field(default_factory=list)

    @property
    def allows_fallback(self) -> bool:
        return self.scene in FALLBACK_ALLOWED_SCENES

    async def chat(self, messages: Any, **kwargs: Any) -> ChatResponse:
        if not self.allows_fallback:
            # 高风险任务：**不换模型**，让失败原样暴露（失败看得见，混进库看不见）
            return await self.primary.chat(messages, **kwargs)
        try:
            return await self.primary.chat(messages, **kwargs)
        except ProviderError as error:
            if not _worth_falling_back(error):
                # 配置类错误不降级：那是要人改配置的，换模型只会把「配置错了」掩盖成「能用」
                raise
            note = FallbackNote(
                scene=self.scene,
                primary=self.primary_name,
                fallback=self.fallback_name,
                reason=str(error),
            )
            response = await self.fallback.chat(messages, **kwargs)
            note.answered_by = response.usage.model
            self.notes.append(note)
            return response


def _worth_falling_back(error: ProviderError) -> bool:
    """只对「上游在抖」的错误降级（可重试类）。

    ⚠️ 额度用尽**不在**其内：免费档用尽是「今天别试了」，
    换备用模型等于把一个明确的结论（今天跑不动）变成一个含糊的结果（换了模型跑出来了）。
    """
    if isinstance(error, ProviderQuotaExhaustedError):
        return False
    return bool(error.retryable)
