"""索引端点的**行为**用例：失败要说清是哪一环。

它们盯的是三类「跑起来才知道」的事，而这三类都不需要真的连向量库或真的花额度：

1. **路径与白名单**：`/admin/index/rebuild`、`/admin/index/reconcile` 必须逐字存在于
   路由表里（Java 契约靠字符串拼，写错一个字就是 404，而报出来的话是「Python 不可用」）；
2. **向量库不可达 → 502，且消息里带 base_url 与集合名**：否则本地直连测试机时
   看到的只是一句「连接失败」，还得自己去猜是地址错了、隧道断了、还是集合不存在；
3. **额度用尽 → 429**：与「稍后重试」分开（免费档是每日 50 次，退避几秒救不了）。
"""

from __future__ import annotations

import pytest

from app.api.v1 import index as index_module
from app.providers.errors import ProviderQuotaExhaustedError
from tests.test_app import EXPOSED_PATHS

REBUILD = "/admin/index/rebuild"
RECONCILE = "/admin/index/reconcile"


class _BrokenStore:
    """连不上的向量库：任何操作都抛，且必须提供 aclose（端点会在 finally 里调它）。"""

    def __init__(self, *_args: object, **_kwargs: object) -> None:
        pass

    async def hashes_by_post(self) -> dict[int, set[str]]:
        raise RuntimeError("Connection refused")

    async def aclose(self) -> None:
        return None


class _BrokenRegistry:
    """嵌入模型侧：额度用尽（retryable=False，退避无用）。"""

    def embedding_model(self) -> object:
        raise ProviderQuotaExhaustedError("免费档每日 50 次已用完，明天 UTC 零点重置")


def test_index_paths_match_the_java_contract() -> None:
    """两个路径都在白名单登记表里 —— 而那张表的另一个用例会断言它们**不是**公开的。"""
    assert REBUILD in EXPOSED_PATHS
    assert RECONCILE in EXPOSED_PATHS


@pytest.mark.asyncio
async def test_reconcile_reports_the_vector_store_it_could_not_reach(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """向量库连不上：502，且消息里必须有 base_url、集合名与「怎么查」的提示。"""
    monkeypatch.setattr(index_module, "QdrantVectorStore", _BrokenStore)

    response = await index_module.reconcile_index()

    assert response.status_code == 200  # 本站口径：HTTP 200 + body 里的 code
    body = response.body.decode()
    assert '"code": 502' in body or '"code":502' in body
    config = index_module.QdrantConfig.from_env()
    assert config.base_url in body
    assert config.collection in body


@pytest.mark.asyncio
async def test_rebuild_reports_quota_exhaustion_as_429(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """额度用尽回 429（不是 502）：它与「向量库坏了」是两件完全不同的事。"""
    monkeypatch.setattr(index_module, "QdrantVectorStore", _BrokenStore)
    monkeypatch.setattr(
        index_module.runtime, "registry", lambda *_a, **_k: _BrokenRegistry()
    )

    from app.schemas.indexing import IndexRebuildRequest

    response = await index_module.rebuild_index(IndexRebuildRequest())

    assert response.status_code == 200
    body = response.body.decode()
    assert '"code": 429' in body or '"code":429' in body
    assert "明天 UTC 零点重置" in body
