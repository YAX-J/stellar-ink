"""测试用的显式桩配置：**测试不该依赖代码里有 Fake 默认值**。

为什么要单独一个模块：四个接口测试（问答 / 流式问答 / Copilot / Agent / 评测）都要
「显式声明这次用桩」，各写一份的结果是它们会慢慢分叉 —— 而分叉的表现是
「某个端点在测试里跑的是真模型」，那种测试要么慢、要么需要密钥、要么根本不起。

`install_fake_providers()` 同时清掉装配缓存：装配来源与语料都是进程级缓存，
只注入不清缓存的话，上一个测试留下的管道与语料会带进下一个测试。
"""

from __future__ import annotations

from collections.abc import Sequence

from app.api.v1.assembly import use_provider_configs
from app.providers import runtime
from app.providers.models import ProviderCapabilities, ProviderConfig


def provider_config(
    role: str,
    *,
    provider: str = "fake",
    model: str = "fake",
    dimension: int | None = None,
    capabilities: ProviderCapabilities | None = None,
) -> ProviderConfig:
    """造一条角色配置（默认就是「面板里把协议选成 fake」的等价物）。"""
    if capabilities is None:
        capabilities = ProviderCapabilities(
            chat=role in {"chat", "fast", "reasoning"},
            embedding=role == "embedding",
            rerank=role == "rerank",
        )
    return ProviderConfig(
        role=role,
        provider=provider,
        base_url="http://fake.local",
        model=model,
        dimension=dimension,
        capabilities=capabilities,
    )


def fake_provider_configs() -> list[ProviderConfig]:
    """三个角色都用桩：问答 / Copilot / Agent / 评测都能跑，且不需要密钥与网络。"""
    return [
        provider_config("chat"),
        provider_config("embedding", dimension=64),
        provider_config("rerank"),
    ]


def install_fake_providers() -> None:
    """显式注入桩配置并清空装配缓存（测试 fixture 里调一次）。

    ⚠️ **个人配置那条来源也要一起装**（M12 起）：问答/ Copilot / Agent 现在会
    `registry_for(user_id)` 取模型，只装全局那份的话，测试里会落到「按用户读数据库」——
    而单测机器上没有库，于是所有问答用例都变成 400「角色 chat 尚未配置」。
    桩的含义是「这次用桩」，所以两处都给同一份桩。
    """
    configs = fake_provider_configs()
    use_provider_configs(configs)
    runtime.use_user_config_source(lambda _user_id: configs)


def install_no_providers() -> None:
    """显式注入**空配置**：用来验证「没配就说没配」，而不是悄悄退回桩。

    刻意不用「不注入」来测这件事：不注入会去读环境变量甚至真库，
    测试会因此依赖跑测机器上有没有 `.env`（曾经真的这么错过一次）。
    """
    use_provider_configs([])
    runtime.use_user_config_source(lambda _user_id: [])


def install_roles(roles: Sequence[str], *, provider: str = "fake") -> None:
    """只配给定角色：用来测「缺哪个角色时错误消息对不对」。"""
    configs = [provider_config(role, provider=provider) for role in roles]
    use_provider_configs(configs)
    runtime.use_user_config_source(lambda _user_id: configs)
