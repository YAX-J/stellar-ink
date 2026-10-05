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

import logging
from collections import OrderedDict
from collections.abc import Callable, Iterable, Sequence
from threading import Lock

from app.providers.config_source import load_own_user_configs, load_provider_configs
from app.providers.errors import UnsupportedCapabilityError
from app.providers.models import ProviderConfig
from app.providers.registry import ProviderRegistry, capability_of
from app.providers.resolver import ProviderResolver

logger = logging.getLogger(__name__)

#: 全局唯一的解析器；`use_provider_configs` 会替换它（只给测试与离线脚本用）
_resolver = ProviderResolver(source=load_provider_configs)

#: 个人配置来源：默认直连库按用户读（`user_id` 覆盖全局，没配的角色回落全局）
UserConfigSource = Callable[[int], Sequence[ProviderConfig]]
_user_source: UserConfigSource = lambda user_id: load_provider_configs(user_id=user_id)  # noqa: E731

#: 「用户**自己**那份」配置来源：**只含他自己的行**，不含站长那份全局。
#: 与上面那个分开是有原因的（借全局密钥去请求用户填的地址 = 偷 Key），见 `saved_config_for`。
OwnUserConfigSource = Callable[[int], Sequence[ProviderConfig]]
_own_user_source: OwnUserConfigSource = (  # noqa: E731 - 与上面同一种「可替换默认实现」的写法
    lambda user_id: load_own_user_configs(user_id)
)

#: 个人解析器缓存上限（有界，见 `_user_resolver` 的说明）
MAX_USER_RESOLVERS = 32
_user_resolvers: OrderedDict[int, ProviderResolver] = OrderedDict()
_user_lock = Lock()

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

    同时清掉个人配置的解析器：测试里换了来源却还留着上一个用例的用户实例，
    会让「隔离」这类断言变成随机通过。
    """
    global _resolver  # noqa: PLW0603 - 有意留这个接缝：装配来源只在启动/测试时确定
    _resolver = ProviderResolver(source=lambda: tuple(configs))
    with _user_lock:
        _user_resolvers.clear()


def use_user_config_source(source: UserConfigSource) -> None:
    """替换「按用户取配置」的来源（测试用）。

    单独一个接缝，是因为它与全局来源**语义不同**：全局那份是「站长配的」，
    用户那份要「用户行覆盖全局行」，两者不能互相替代。
    """
    global _user_source  # noqa: PLW0603 - 同上：只在启动/测试时确定
    _user_source = source
    with _user_lock:
        _user_resolvers.clear()


def use_own_user_config_source(source: OwnUserConfigSource) -> None:
    """替换「取用户**自己**那份配置」的来源（测试用）。

    它与 `use_user_config_source` **不是**同一个东西：那个是「覆盖 + 回落」的合并结果，
    这个只含用户自己的行（拉模型清单借密钥时用，绝不能借到站长那份，见 `saved_config_for`）。
    测试里两处都要显式装：只装前者时「借密钥」那条路径会落到真实读库上。
    """
    global _own_user_source  # noqa: PLW0603 - 同上：只在启动/测试时确定
    _own_user_source = source


# --------------------------------------------------------------- 个人配置（读者/作者）


def registry_for(user_id: int | None) -> ProviderRegistry:
    """取**这个用户**的注册表：他的 chat/fast/reasoning 覆盖全局，其余回落到全局。

    ⚠️ 检索侧（embedding/rerank）**必须**继续用全局的 `registry()`：
    向量索引只有一份，用别的嵌入模型去检索得到的是错的结果，不是「差一点」。
    所以本函数只该被「生成」类调用点使用（问答、Copilot、Agent）。
    """
    if not user_id:
        return registry()
    return _user_resolver(int(user_id)).registry()


def fingerprint_for(user_id: int | None) -> str:
    """该用户生效配置的指纹（用于「配置变了没有」的判断与日志）。"""
    if not user_id:
        return fingerprint()
    return _user_resolver(int(user_id)).fingerprint


def require_roles_for(user_id: int | None, *roles: str) -> None:
    """按用户预检角色；错误消息带上「是个人配置还是全局配置」的线索。"""
    if not user_id:
        require_roles(*roles)
        return
    current = registry_for(user_id)
    missing = [
        role
        for role in roles
        if (config := current.config_of(role)) is None
        or not config.capabilities.supports(capability_of(role))
    ]
    if missing:
        raise UnsupportedCapabilityError(
            "角色 " + "、".join(missing) + " 尚未配置模型",
            detail=(
                "可以在「账号 → 我的 AI 模型」里自己配一个，"
                "或用站长的全局配置（未配的角色会自动回落到全局）"
            ),
        )


def _user_resolver(user_id: int) -> ProviderResolver:
    """按用户缓存解析器（有界 LRU）。

    为什么要缓存：每个解析器持有一个 httpx 连接池，每次问答重建等于每次都重新握 TLS。
    为什么要**有界**：用户是无限的，不设上限就是「每个来过的人都留一个连接池」——
    那是内存泄漏，只是泄漏得比较慢。被淘汰的用户下次访问重建（多一次握手），
    这个代价换内存有界是划算的。
    """
    with _user_lock:
        existing = _user_resolvers.get(user_id)
        if existing is not None:
            _user_resolvers.move_to_end(user_id)
            return existing
        resolver = ProviderResolver(source=lambda: _user_source(user_id))
        _user_resolvers[user_id] = resolver
        while len(_user_resolvers) > MAX_USER_RESOLVERS:
            evicted, _ = _user_resolvers.popitem(last=False)
            logger.info("个人模型配置缓存已满，淘汰用户 %s 的解析器（下次访问时重建）", evicted)
        return resolver


def saved_config_for(user_id: int | None, role: str) -> ProviderConfig | None:
    """取「这个用户**自己**保存的某角色配置」，取不到返回 None。

    用途只有一个：拉模型清单时 `apiKey` 留空，就用他自己已经存好的那把密钥。

    ⚠️ **只认他自己的行，绝不回落到站长那份全局配置**（这与 `registry_for` 的回落语义
    刻意不同）。原因不是洁癖而是漏洞：拉清单的 `base_url` 是**用户自己填的**，
    一旦把全局那份的密钥借出来，任何登录用户都能让服务端把**站长的密钥**发往他控制的公网地址 ——
    一次请求偷一把 Key。所以他用自己的密钥、去自己填的地址；没配过就填表单（400 会这么说）。

    ⚠️ **为什么不走 `registry_for(user_id)`**（除了上面那条）：注册表是按配置指纹缓存的，
    而指纹刻意**不含 `api_key`**（见 `ProviderConfig.fingerprint`）。于是「只换 Key、不换端点」
    时指纹不变，缓存里那份仍是**旧密钥** —— 表现是「刚在面板里换了 Key，拉清单却说密钥无效」，
    而用户改的是一个已经正确的值。这里每次都问一次来源，拿到的就是当前那把。

    ⚠️ 返回值里的 `api_key` 是**明文**（调用方要拿它发请求）。调用方不得把它写进
    日志、审计或响应 —— 这是本函数存在的前提，不是注意事项。
    """
    if not user_id:
        # 没有身份就没有「他自己的配置」：全局那份不在本函数的语义里（见上）
        return None
    for config in _own_user_source(int(user_id)):
        if config.role == role:
            return config
    return None


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
