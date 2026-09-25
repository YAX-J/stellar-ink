"""运行期 Provider 解析器的**唯一实例**：面板配置是模型的唯一来源。

为什么要有这个模块：`ProviderResolver` 是可注入的（测试与离线脚本各给自己的一份），
但**业务端点必须共用同一个实例** —— 各建一个的话，「配置改了立刻生效」与实例缓存
都会变成各管一段：面板换了模型，问答换了、Copilot 还在用旧的，而且日志里看不出来。
所以「配置从哪来」只在这里决定一次，端点统一通过 `registry()` 取当前注册表。

**没有默认模型**：空配置时 `registry.chat_model()` 抛「角色 chat 尚未配置模型」，
端点把它转成 400 并附上「配置是从哪读的」，绝不回退到 Fake ——
回退会让「忘了配」表现成「回答质量差」，是最难查的一类问题。
Fake 只能作为**显式配置**存在（面板里把协议选成 `fake`）。
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence

from app.providers.config_source import load_provider_configs
from app.providers.errors import UnsupportedCapabilityError
from app.providers.models import ProviderConfig
from app.providers.registry import ProviderRegistry, capability_of
from app.providers.resolver import ProviderResolver

#: 全局唯一的解析器；`use_provider_configs` 会替换它（只给测试与离线脚本用）
_resolver = ProviderResolver(source=load_provider_configs)

#: 「缺哪个角色」时统一给出的操作指引：只说「没配」等于只说了一半
CONFIGURE_HINT = "请在「AI 实验室 → 模型配置」里填写该角色的端点与密钥"


def registry() -> ProviderRegistry:
    """取当前注册表（配置指纹变了会自动重建；实例按指纹缓存）。"""
    return _resolver.registry()


def fingerprint() -> str:
    """当前配置指纹；**只有调用过 `registry()` 之后才有意义**（否则是空串）。"""
    return _resolver.fingerprint


def invalidate() -> None:
    """强制下次重新读配置（面板保存后 / 测试）。"""
    _resolver.invalidate()


def use_provider_configs(configs: Sequence[ProviderConfig]) -> None:
    """把解析器固定到给定配置（测试与离线脚本用）。

    它**不会**替你造默认模型：调用方必须显式列出每个角色，
    包括「我要用 fake」也要显式写成 `provider="fake"` ——
    这正是面板里把协议选成 Fake 的等价物。
    """
    global _resolver  # noqa: PLW0603 - 有意留这个接缝：装配来源只在启动/测试时确定
    _resolver = ProviderResolver(source=lambda: tuple(configs))


def missing_roles(roles: Iterable[str]) -> list[str]:
    """列出「当前配置里不可用」的角色（没配，或配了但能力不符）。"""
    return [role for role, _ in _unusable_roles(roles)]


def _unusable_roles(roles: Iterable[str]) -> list[tuple[str, bool]]:
    """`(角色, 是否已填但能力不符)`；顺序与入参一致，便于错误消息稳定可读。"""
    current = registry()
    result: list[tuple[str, bool]] = []
    for role in roles:
        capability = capability_of(role)  # 未知角色直接抛错：这是编程错误，不该被吞
        config = current.config_of(role)
        if config is None:
            result.append((role, False))
        elif not config.capabilities.supports(capability):
            result.append((role, True))
    return result


def require_roles(*roles: str) -> None:
    """预检若干角色；缺任何一个就抛一条**可直接照做**的错误。

    为什么要预检而不是让调用点各自抛：评测跑一轮要先嵌入整个语料，
    如果等第一路 Dense 策略跑到一半才发现「嵌入模型没配」，
    用户会先等几十秒再拿到一个错误。预检让它在 0 成本时就说清楚。

    「没配」与「填了但能力不符」分开报：后者说成「没配」会让人反复检查一个
    明明填好了的表单，而真正的问题（能力勾选不对）永远不会被发现。
    """
    unusable = _unusable_roles(roles)
    if not unusable:
        return
    not_configured = [role for role, mismatch in unusable if not mismatch]
    mismatch = [role for role, wrong in unusable if wrong]
    parts: list[str] = []
    if not_configured:
        parts.append("角色 " + "、".join(not_configured) + " 尚未配置模型")
    if mismatch:
        parts.append("角色 " + "、".join(mismatch) + " 已填但缺少所需能力（见该角色的能力勾选）")
    raise UnsupportedCapabilityError("；".join(parts), detail=CONFIGURE_HINT)
