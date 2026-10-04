"""嵌入模型指纹护栏：换模型之后**必须报错**，而不是静静地返回错的结果。

为什么单独盯它：向量库对「维度」有硬约束（不同维度直接失败，算是天然保护），
但**同维度、不同模型**不会失败 —— 相似度算出来的东西毫无意义。
这类错的表现是「检索质量突然很差」，而人会先去怀疑切块、提示词、rerank，
最后才怀疑索引是用另一个模型建的。所以留一条记录、并在用之前比一下，成本极低、收益很大。
"""

from __future__ import annotations

import httpx
import pytest

from app.providers.errors import ProviderError
from app.rag.qdrant_store import QdrantConfig, QdrantVectorStore

COLLECTION = "stellar_ink_chunks"


def _store(routes: dict[tuple[str, str], httpx.Response]) -> QdrantVectorStore:
    def handler(request: httpx.Request) -> httpx.Response:
        key = (request.method, request.url.path)
        if key not in routes:
            return httpx.Response(404, json={"status": {"error": f"未预置 {key}"}})
        return routes[key]

    return QdrantVectorStore(
        QdrantConfig(collection=COLLECTION), transport=httpx.MockTransport(handler)
    )


def _scroll_point(fingerprint: str | None) -> httpx.Response:
    payload: dict[str, object] = {"chunkId": "p1:v1:c0", "postId": 1}
    if fingerprint is not None:
        payload["modelFingerprint"] = fingerprint
    return httpx.Response(200, json={"result": {"points": [{"id": 1, "payload": payload}]}})


@pytest.mark.asyncio
async def test_reads_the_fingerprint_of_an_indexed_point() -> None:
    store = _store({("POST", f"/collections/{COLLECTION}/points/scroll"): _scroll_point("fp-a")})

    assert await store.sample_model_fingerprint() == "fp-a"
    await store.aclose()


@pytest.mark.asyncio
async def test_empty_index_is_not_a_mismatch() -> None:
    """还没建索引（或旧数据没有该字段）不算不一致 —— 不能因此挡住第一次写入。"""
    store = _store(
        {
            ("POST", f"/collections/{COLLECTION}/points/scroll"): httpx.Response(
                200, json={"result": {"points": []}}
            )
        }
    )

    assert await store.sample_model_fingerprint() is None
    assert await store.assert_model_fingerprint("fp-new") is None
    await store.aclose()


@pytest.mark.asyncio
async def test_mismatched_fingerprint_raises_with_a_way_out() -> None:
    """不一致时必须抛错，且消息里给出「怎么修」（重建索引）。"""
    store = _store(
        {("POST", f"/collections/{COLLECTION}/points/scroll"): _scroll_point("fp-old")}
    )

    with pytest.raises(ProviderError) as error:
        await store.assert_model_fingerprint("fp-new")

    message = str(error.value)
    assert "fp-old" in message and "fp-new" in message
    assert "整库重建" in message
    await store.aclose()


@pytest.mark.asyncio
async def test_matching_fingerprint_passes() -> None:
    store = _store({("POST", f"/collections/{COLLECTION}/points/scroll"): _scroll_point("fp-a")})

    assert await store.assert_model_fingerprint("fp-a") == "fp-a"
    await store.aclose()
