"""测试用的显式桩配置：**测试不该依赖代码里有 Fake 默认值**。

为什么要单独一个模块：四个接口测试（问答 / 流式问答 / Copilot / Agent / 评测）都要
「显式声明这次用桩」，各写一份的结果是它们会慢慢分叉 —— 而分叉的表现是
「某个端点在测试里跑的是真模型」，那种测试要么慢、要么需要密钥、要么根本不起。

`install_fake_providers()` 同时清掉装配缓存：装配来源与语料都是进程级缓存，
只注入不清缓存的话，上一个测试留下的管道与语料会带进下一个测试。
"""

from __future__ import annotations

from collections.abc import Sequence

from app.api.v1 import assembly
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


def _force_in_memory_retrieval() -> None:
    """单测一律走**内存通路**，不受 `.env` 里部署开关的影响。

    为什么必须显式关掉（真实踩到）：`AI_DENSE_STORE_ENABLED` 是**导入期读一次**的部署开关，
    而 `corpus`/`assembly` 会 load_dotenv —— 于是「本机 `.env` 一改成 true」，
    单测就悄悄开始连真实 Qdrant：跨公网、依赖外部服务，而且库里是**真实嵌入模型**的指纹，
    与测试用的 Fake 嵌入不一致 → 指纹护栏当场抛错，一次红四条（日志里看像代码坏了）。

    单测要的是**确定的输入**，不是「跑测机器上碰巧配了什么」。
    代价是这条路径的接线不再被这些用例覆盖 —— 所以另有
    `tests/test_assembly_dense_store.py` 用假 store 把「开关打开时真的传 dense_store」钉住。
    """
    assembly.DENSE_STORE_ENABLED = False


def install_fake_providers() -> None:
    """显式注入桩配置并清空装配缓存（测试 fixture 里调一次）。

    ⚠️ **个人配置那条来源也要一起装**（M12 起）：问答/ Copilot / Agent 现在会
    `registry_for(user_id)` 取模型，只装全局那份的话，测试里会落到「按用户读数据库」——
    而单测机器上没有库，于是所有问答用例都变成 400「角色 chat 尚未配置」。
    桩的含义是「这次用桩」，所以两处都给同一份桩。

    ⚠️ 还有**第三个**来源「用户自己那份」（`use_own_user_config_source`，拉模型清单借密钥用）：
    它同样要显式装，否则那条路径会去真读库 —— 而它借的是**明文密钥**，
    让测试落到真实读库上是最不该有的意外（本地有 `.env` 时会真的连上库）。
    """
    _force_in_memory_retrieval()
    configs = fake_provider_configs()
    use_provider_configs(configs)
    runtime.use_user_config_source(lambda _user_id: configs)
    runtime.use_own_user_config_source(lambda _user_id: configs)


def install_no_providers() -> None:
    """显式注入**空配置**：用来验证「没配就说没配」，而不是悄悄退回桩。

    刻意不用「不注入」来测这件事：不注入会去读环境变量甚至真库，
    测试会因此依赖跑测机器上有没有 `.env`（曾经真的这么错过一次）。
    """
    _force_in_memory_retrieval()
    use_provider_configs([])
    runtime.use_user_config_source(lambda _user_id: [])
    runtime.use_own_user_config_source(lambda _user_id: [])


def install_roles(roles: Sequence[str], *, provider: str = "fake") -> None:
    """只配给定角色：用来测「缺哪个角色时错误消息对不对」。"""
    _force_in_memory_retrieval()
    configs = [provider_config(role, provider=provider) for role in roles]
    use_provider_configs(configs)
    runtime.use_user_config_source(lambda _user_id: configs)
    runtime.use_own_user_config_source(lambda _user_id: configs)
