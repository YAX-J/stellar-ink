"""`assembly.pipeline_for` 的向量库接缝：**开关打开时真的把 dense_store 传下去**。

为什么单独一个文件：`tests/fake_providers.py` 为了让单测**确定**，强制走内存通路
（否则本机 `.env` 一改，单测就会去连真实 Qdrant）。代价是那条接线不再被别的用例覆盖 ——
而「装了一个一开就崩的开关」正是最需要钉住的一类错误（默认关闭时它根本不会被走到）。

这里用**假 store** 覆盖，既不连外部服务，也不依赖索引是否存在。
"""

from __future__ import annotations

from typing import Any

import pytest

from app.api.v1 import assembly
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline
from app.rag.qdrant_store import VectorHit
from tests.fake_providers import install_fake_providers


class _FakeDenseStore:
    """只够证明「被传进管道并被用到」的最小向量库。"""

    def __init__(self) -> None:
        self.searched = 0
        self.fingerprint_checks = 0

    async def search(self, vector: Any, *, top_k: int, score_threshold: Any = None):
        self.searched += 1
        return [VectorHit(point_id=1, chunk_id="p1:v1:c0", post_id=1, score=0.9)]

    async def assert_model_fingerprint(self, expected: Any = None) -> Any:
        self.fingerprint_checks += 1
        return None


@pytest.fixture()
def dense_store(monkeypatch: pytest.MonkeyPatch) -> _FakeDenseStore:
    install_fake_providers()
    fake = _FakeDenseStore()
    # 两步都要替：常量决定「传不传」，单例工厂决定「传谁」
    monkeypatch.setattr(assembly, "DENSE_STORE_ENABLED", True)
    monkeypatch.setattr(assembly, "_shared_dense_store", lambda: fake)
    monkeypatch.setattr(assembly, "_pipelines", {})
    return fake


def test_switch_on_passes_the_store_into_the_pipeline(dense_store: _FakeDenseStore) -> None:
    pipeline = assembly.pipeline_for(RetrievalConfig(enable_sparse=True, enable_dense=False))

    assert isinstance(pipeline, RetrievalPipeline)
    assert pipeline.dense_store is dense_store, "开关打开时必须真的把 dense_store 传下去"


def test_switch_off_keeps_the_in_memory_path(
    monkeypatch: pytest.MonkeyPatch, dense_store: _FakeDenseStore
) -> None:
    """默认（关）时不许传 store：内存通路是「不依赖外部组件」的那条路。"""
    monkeypatch.setattr(assembly, "DENSE_STORE_ENABLED", False)
    monkeypatch.setattr(assembly, "_pipelines", {})

    pipeline = assembly.pipeline_for(RetrievalConfig(enable_sparse=True, enable_dense=False))

    assert pipeline.dense_store is None


def test_switch_is_part_of_the_pipeline_cache_key(
    monkeypatch: pytest.MonkeyPatch, dense_store: _FakeDenseStore
) -> None:
    """开关必须进缓存键。

    否则「改了开关但管道还是旧的」—— 而表现只是「怎么还走内存」，日志里什么都看不出来。
    """
    config = RetrievalConfig(enable_sparse=True, enable_dense=False)
    on = assembly.pipeline_for(config)

    monkeypatch.setattr(assembly, "DENSE_STORE_ENABLED", False)
    off = assembly.pipeline_for(config)

    assert on is not off, "开关变了就该换一条管道（缓存键里必须含它）"
    assert on.dense_store is dense_store and off.dense_store is None
