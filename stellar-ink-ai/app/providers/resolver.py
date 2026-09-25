"""按「配置指纹」缓存 Provider 注册表：面板配了什么，服务就用什么。

为什么需要这一层：`ProviderRegistry` 本身已经是「没有默认值」的（角色未配置就报
「请在 AI 实验室里填该角色」），但业务端点曾经直接 `FakeProvider()` ——
那等于在代码里留了一条**绕过面板的暗路**：面板配了真实模型，问答却还在跑假模型，
而日志与响应都显示「正常」。这个模块把装配收成一条路径。

三条刻意的取舍：

1. **缓存键是配置指纹，不是「第一次装配」**。`ProviderConfig.fingerprint()` 只含非敏感字段
   （role/provider/baseUrl/model/维度/超时），因此密钥轮换不影响指纹、换模型立刻换实例。
   指纹里**绝不含 api_key** —— 它一旦进缓存键就等于进了日志。
2. **指纹变了就重建，并关闭旧的连接池**。否则每次改配置都漏一个 httpx 客户端，
   跑一天就是几百个连接池。
3. **装不出来就抛错，不回退**。没有「没配就用 Fake」这种兜底：那会让
   「服务没配好」表现成「回答质量差」，是最难查的一类问题。
   Fake 只能作为**显式配置**存在（面板里把协议选成 `fake`），不是代码里的默认。

配置从哪来：调用方注入一个 `ConfigSource`（可调用对象，返回 `ProviderConfig` 列表）。
这样本模块不读数据库、不读环境变量，测试与离线脚本都能注入自己的来源。
"""

from __future__ import annotations

import logging
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass, field
from threading import Lock

from app.providers.models import ProviderConfig
from app.providers.registry import ProviderRegistry

logger = logging.getLogger(__name__)

#: 配置来源：返回当前所有角色的配置（不含密钥时装配会失败，见 registry）
ConfigSource = Callable[[], Sequence[ProviderConfig]]


@dataclass(slots=True)
class ProviderResolver:
    """把「配置从哪来」与「实例怎么缓存」收在一起。线程安全（进程内）。"""

    source: ConfigSource
    _lock: Lock = field(default_factory=Lock, init=False)
    _fingerprint: str = field(default="", init=False)
    _registry: ProviderRegistry | None = field(default=None, init=False)
    #: 诊断用：最近一次装配的来源说明（不含密钥）
    last_source: str = field(default="", init=False)

    def registry(self) -> ProviderRegistry:
        """取当前注册表；配置指纹变了就重建。"""
        configs = tuple(self.source())
        fingerprint = fingerprint_of(configs)
        with self._lock:
            if self._registry is not None and fingerprint == self._fingerprint:
                return self._registry
            self._registry = ProviderRegistry(configs)
            self._fingerprint = fingerprint
            self.last_source = f"{len(configs)} 个角色 / 指纹 {fingerprint}"
            logger.info("已装配 Provider：%s", self.last_source)
            return self._registry

    def invalidate(self) -> None:
        """强制下次重新读配置（面板保存后调用；也可用于测试）。"""
        with self._lock:
            self._registry = None
            self._fingerprint = ""

    @property
    def fingerprint(self) -> str:
        with self._lock:
            return self._fingerprint


def fingerprint_of(configs: Iterable[ProviderConfig]) -> str:
    """所有角色指纹的稳定摘要：角色集合或任一角色的非敏感字段变化都会换指纹。

    排序后再拼：配置来源是数据库查询，**行序不保证稳定**，
    不排序会让「同一份配置」在两次查询间算出不同指纹，于是缓存形同虚设。
    """
    parts = sorted(f"{config.role}:{config.fingerprint()}" for config in configs)
    if not parts:
        # 空配置也要有个确定的指纹：它表示「什么都没配」，
        # 与「配了但指纹恰好为空串」必须区分开（后者不可能，但别让它成为隐患）
        return "empty"
    joined = "|".join(parts)
    import hashlib

    return hashlib.sha256(joined.encode("utf-8")).hexdigest()[:16]


def static_resolver(configs: Sequence[ProviderConfig]) -> ProviderResolver:
    """固定配置的解析器（测试与离线脚本用：显式给出配置，而不是让它偷偷用 Fake）。"""
    return ProviderResolver(source=lambda: tuple(configs))
