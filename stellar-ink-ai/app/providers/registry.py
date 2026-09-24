"""按「逻辑角色」装配与路由模型：业务代码只说「我需要 chat」，不关心背后是哪家。

角色 → 能力 的映射是固定的（见 `_ROLE_CAPABILITY`）：
`embedding` 只要嵌入模型，`rerank` 只要重排模型，其余角色都需要对话能力。
这样「给 embedding 角色配了一个纯 chat 模型」会在**取实例时**就报出可操作的错误，
而不是等调用到一半才发现。

配置从哪来：A1 阶段由 ai-service 的 `/ai/admin/providers/runtime` 提供（面板写库、
Java 解密）；Python 侧只负责把它变成实例。因此本模块**不读环境变量、不读数据库**，
便于测试与将来替换来源。
"""

from __future__ import annotations

from collections.abc import Iterable

from app.providers.base import ChatModel, EmbeddingModel, RerankModel
from app.providers.errors import ProviderError, UnsupportedCapabilityError
from app.providers.fake import FakeProvider
from app.providers.models import ProviderConfig
from app.providers.openai_compatible import OpenAICompatibleProvider

#: 角色 → 该角色必须具备的能力
_ROLE_CAPABILITY: dict[str, str] = {
    "chat": "chat",
    "fast": "chat",
    "reasoning": "chat",
    "embedding": "embedding",
    "rerank": "rerank",
}

#: 需要对话模型的角色（供上层做「哪些角色必须配」的提示）
CHAT_ROLES: frozenset[str] = frozenset({"chat", "fast", "reasoning"})


def capability_of(role: str) -> str:
    """取某个角色所需的能力；未知角色直接报错，避免「猜一个能力」导致的静默错配。"""
    capability = _ROLE_CAPABILITY.get(role)
    if capability is None:
        raise ProviderError(
            f"未知的模型角色：{role}",
            detail="可选角色：" + "/".join(sorted(_ROLE_CAPABILITY)),
        )
    return capability


class ProviderRegistry:
    """按角色持有 Provider 实例，并在取用时校验能力是否匹配。"""

    def __init__(self, configs: Iterable[ProviderConfig] = ()) -> None:
        self._configs: dict[str, ProviderConfig] = {}
        self._instances: dict[str, object] = {}
        for config in configs:
            self.register(config)

    def register(self, config: ProviderConfig) -> None:
        # 同一角色重复注册视为「后配置覆盖前配置」，与面板按角色唯一保存的语义一致
        self._instances.pop(config.role, None)
        self._configs[config.role] = config

    @property
    def roles(self) -> list[str]:
        return sorted(self._configs)

    def config_of(self, role: str) -> ProviderConfig | None:
        return self._configs.get(role)

    def safe_summary(self) -> list[dict[str, object]]:
        """全部角色的脱敏摘要，用于面板/日志（不含密钥）。"""
        return [self._configs[role].safe_summary() for role in self.roles]

    def has(self, role: str) -> bool:
        return role in self._configs

    def _get_instance(self, role: str) -> object:
        # 先判角色是否合法：非法角色不该被报成「未配置」，否则排查时会去填一个根本不存在的角色
        capability = capability_of(role)
        config = self._configs.get(role)
        if config is None:
            raise UnsupportedCapabilityError(
                f"角色 {role} 尚未配置模型",
                detail="请在「AI 实验室 → 模型配置」里填写该角色的端点与密钥",
            )
        if not config.capabilities.supports(capability):
            raise UnsupportedCapabilityError(
                f"角色 {role} 需要 {capability} 能力，但 {config.provider} 声明为 "
                f"{config.capabilities.describe()}",
            )
        if role not in self._instances:
            self._instances[role] = self._build(config)
        return self._instances[role]

    def _build(self, config: ProviderConfig) -> object:
        if config.provider == "fake":
            return FakeProvider(config)
        if config.provider == "openai_compatible":
            return OpenAICompatibleProvider(config)
        raise ProviderError(
            f"未知的 provider 实现：{config.provider}",
            detail="当前支持 openai_compatible 与 fake",
        )

    # 三个具名 getter：调用方据此获得类型提示，不必自己做 isinstance 收窄
    def chat_model(self, role: str = "chat") -> ChatModel:
        return self._get_instance(role)  # type: ignore[return-value]

    def embedding_model(self, role: str = "embedding") -> EmbeddingModel:
        return self._get_instance(role)  # type: ignore[return-value]

    def rerank_model(self, role: str = "rerank") -> RerankModel:
        return self._get_instance(role)  # type: ignore[return-value]

    async def aclose(self) -> None:
        """释放底层 HTTP 连接池。"""
        for instance in self._instances.values():
            closer = getattr(instance, "aclose", None)
            if closer is not None:
                await closer()
        self._instances.clear()
