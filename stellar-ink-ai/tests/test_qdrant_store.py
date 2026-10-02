"""Qdrant 适配层的协议测试：不起容器，用 MockTransport 把线上协议钉死。

为什么值得这么测：这一层的价值全在「说对了协议」——路径、查询参数、请求体字段名、
响应取值路径、错误分类。写错任何一处，**只会在接上真库的那一刻**以「检索结果莫名其妙」的形式暴露。
用 MockTransport 把请求原样记下来，就能在没有 Qdrant 的机器上把它们断言掉；
等 SSH 隧道打通后，再跑一次真实冒烟即可（`scripts/qdrant_smoke.py`）。
"""

from __future__ import annotations

import json

import httpx
import pytest

from app.providers.errors import (
    ProviderAuthError,
    ProviderError,
    ProviderRateLimitError,
    ProviderTimeoutError,
    ProviderUnavailableError,
)
from app.rag.qdrant_store import (
    QdrantConfig,
    QdrantVectorStore,
    VectorPoint,
    point_id_for,
)

COLLECTION = "stellar_ink_chunks"


class _Recorder:
    """记录请求并返回预置响应；未预置的路径直接失败，避免「悄悄多打了一次请求」。"""

    def __init__(self, routes: dict[tuple[str, str], httpx.Response]) -> None:
        self.routes = routes
        self.requests: list[httpx.Request] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        key = (request.method, request.url.path)
        if key not in self.routes:
            return httpx.Response(404, json={"status": {"error": f"未预置 {key}"}})
        return self.routes[key]

    @property
    def paths(self) -> list[str]:
        return [request.url.path for request in self.requests]


def _store(recorder: _Recorder, **overrides) -> QdrantVectorStore:
    config = QdrantConfig(collection=COLLECTION, **overrides)
    return QdrantVectorStore(config, transport=httpx.MockTransport(recorder.handler))


def _point(chunk_id: str = "p1:v1:c0", post_id: int = 1, size: int = 3) -> VectorPoint:
    return VectorPoint(
        chunk_id=chunk_id,
        post_id=post_id,
        vector=[0.1] * size,
        payload={"text": "片段", "headingPath": "一、缘起"},
    )


def test_point_id_is_deterministic_and_uint64_safe() -> None:
    assert point_id_for("p1:v1:c0") == point_id_for("p1:v1:c0"), "同一 chunk 必须同一 id"
    assert point_id_for("p1:v1:c0") != point_id_for("p1:v1:c1")
    assert 0 < point_id_for("p1:v1:c0") < 2**63, "超过 2^63 会被 JSON 工具链当成负数"
    with pytest.raises(ValueError, match="chunk_id"):
        point_id_for("")


def test_config_rejects_bad_values_and_hides_the_key() -> None:
    with pytest.raises(ValueError, match="base_url"):
        QdrantConfig(base_url="127.0.0.1:6333")
    with pytest.raises(ValueError, match="collection"):
        QdrantConfig(collection="  ")
    with pytest.raises(ValueError, match="timeout_ms"):
        QdrantConfig(timeout_ms=0)
    with pytest.raises(ValueError, match="distance"):
        QdrantConfig(distance="cosine")

    described = QdrantConfig(api_key="qdrant-secret").describe()
    assert "qdrant-secret" not in json.dumps(described), "api_key 绝不能出现在摘要里"
    assert described["auth"] == "api_key"


def test_client_ignores_environment_proxies() -> None:
    """内部服务必须绕开环境代理：否则 api-key 会进代理，且「隧道没开」会被误报成 502。

    这是实测出来的：本机装了代理时，httpx 默认会把 `http://127.0.0.1:6333/` 交给代理，
    拿回一个空的 502 —— 看起来像 Qdrant 报错，其实请求根本没到 Qdrant。
    """
    recorder = _Recorder({})
    store = _store(recorder)

    assert store._client._trust_env is False


async def test_api_key_becomes_the_qdrant_header() -> None:
    recorder = _Recorder({("GET", "/"): httpx.Response(200, json={"title": "qdrant"})})
    store = _store(recorder, api_key="qdrant-secret")

    await store.health()

    assert recorder.requests[0].headers["api-key"] == "qdrant-secret"
    await store.aclose()


async def test_no_api_key_means_no_header() -> None:
    recorder = _Recorder({("GET", "/"): httpx.Response(200, json={"title": "qdrant"})})
    store = _store(recorder)

    await store.health()

    assert "api-key" not in recorder.requests[0].headers
    await store.aclose()


async def test_health_keeps_only_whitelisted_fields() -> None:
    recorder = _Recorder(
        {
            ("GET", "/"): httpx.Response(
                200,
                json={
                    "title": "qdrant - vector search engine",
                    "version": "1.12.4",
                    "commit": "abc",
                },
            )
        }
    )
    store = _store(recorder)

    health = await store.health()

    assert health == {"title": "qdrant - vector search engine", "version": "1.12.4"}
    assert "commit" not in health, "白名单之外的不进日志"
    await store.aclose()


async def test_collection_info_reads_dimension_and_count() -> None:
    recorder = _Recorder(
        {
            ("GET", f"/collections/{COLLECTION}"): httpx.Response(
                200,
                json={
                    "result": {
                        "status": "green",
                        "points_count": 41,
                        "config": {"params": {"vectors": {"size": 1024, "distance": "Cosine"}}},
                    }
                },
            )
        }
    )
    store = _store(recorder)

    info = await store.collection_info()

    assert info.exists is True
    assert info.dimension == 1024
    assert info.points_count == 41
    assert store.dimension == 1024
    await store.aclose()


async def test_missing_collection_is_not_an_error() -> None:
    """首次建索引时集合本来就不存在 —— 这不是故障，不该抛错。"""
    recorder = _Recorder(
        {("GET", f"/collections/{COLLECTION}"): httpx.Response(404, json={"status": "not found"})}
    )
    store = _store(recorder)

    info = await store.collection_info()

    assert info.exists is False
    assert store.dimension is None
    await store.aclose()


async def test_ensure_collection_creates_with_cosine_and_size() -> None:
    recorder = _Recorder(
        {
            ("GET", f"/collections/{COLLECTION}"): httpx.Response(404, json={}),
            ("PUT", f"/collections/{COLLECTION}"): httpx.Response(200, json={"result": True}),
        }
    )
    store = _store(recorder)

    info = await store.ensure_collection(dimension=1024)

    assert info == type(info)(exists=True, dimension=1024)
    body = json.loads(recorder.requests[-1].content)
    assert body == {"vectors": {"size": 1024, "distance": "Cosine"}}
    await store.aclose()


async def test_ensure_collection_skips_creation_when_dimension_matches() -> None:
    recorder = _Recorder(
        {
            ("GET", f"/collections/{COLLECTION}"): httpx.Response(
                200,
                json={"result": {"config": {"params": {"vectors": {"size": 1024}}}}},
            )
        }
    )
    store = _store(recorder)

    await store.ensure_collection(dimension=1024)

    assert recorder.paths == [f"/collections/{COLLECTION}"], "维度一致时不该重复建集合"
    await store.aclose()


async def test_ensure_collection_refuses_dimension_mismatch() -> None:
    """维度不一致 = 换了嵌入模型：必须报错，绝不能悄悄重建把旧向量删掉。"""
    recorder = _Recorder(
        {
            ("GET", f"/collections/{COLLECTION}"): httpx.Response(
                200,
                json={"result": {"config": {"params": {"vectors": {"size": 768}}}}},
            )
        }
    )
    store = _store(recorder)

    with pytest.raises(ValueError, match="768"):
        await store.ensure_collection(dimension=1024)
    await store.aclose()


async def test_ensure_collection_can_recreate_explicitly() -> None:
    recorder = _Recorder(
        {
            ("GET", f"/collections/{COLLECTION}"): httpx.Response(
                200,
                json={"result": {"config": {"params": {"vectors": {"size": 768}}}}},
            ),
            ("DELETE", f"/collections/{COLLECTION}"): httpx.Response(200, json={"result": True}),
            ("PUT", f"/collections/{COLLECTION}"): httpx.Response(200, json={"result": True}),
        }
    )
    store = _store(recorder)

    info = await store.ensure_collection(dimension=1024, recreate=True)

    assert info.dimension == 1024
    assert recorder.paths == [
        f"/collections/{COLLECTION}",
        f"/collections/{COLLECTION}",
        f"/collections/{COLLECTION}",
    ]
    await store.aclose()


async def test_upsert_sends_wait_true_and_camel_case_payload() -> None:
    recorder = _Recorder(
        {("PUT", f"/collections/{COLLECTION}/points"): httpx.Response(200, json={"result": {}})}
    )
    store = _store(recorder)

    written = await store.upsert([_point(), _point("p1:v1:c1", post_id=2)])

    request = recorder.requests[-1]
    assert request.url.params["wait"] == "true", "不等写入完成会让「刚写完就查」查不到"
    body = json.loads(request.content)
    assert len(body["points"]) == 2
    first = body["points"][0]
    assert first["id"] == point_id_for("p1:v1:c0")
    assert first["vector"] == [0.1, 0.1, 0.1]
    assert first["payload"]["chunkId"] == "p1:v1:c0"
    assert first["payload"]["postId"] == 1
    assert first["payload"]["headingPath"] == "一、缘起", "原有 payload 不能被覆盖掉"
    assert written == 2
    await store.aclose()


async def test_upsert_without_points_skips_http() -> None:
    recorder = _Recorder({})
    store = _store(recorder)

    assert await store.upsert([]) == 0
    assert recorder.requests == []
    await store.aclose()


async def test_delete_collection_is_explicit_and_clears_dimension() -> None:
    """删集合是破坏性操作：只在调用方明确要求时发生（recreate 或直接调用）。"""
    recorder = _Recorder(
        {
            ("GET", f"/collections/{COLLECTION}"): httpx.Response(
                200, json={"result": {"config": {"params": {"vectors": {"size": 8}}}}}
            ),
            ("DELETE", f"/collections/{COLLECTION}"): httpx.Response(200, json={"result": True}),
        }
    )
    store = _store(recorder)
    await store.collection_info()
    assert store.dimension == 8

    await store.delete_collection()

    assert store.dimension is None, "集合都没了，缓存的维度必须一起忘掉"
    await store.aclose()


async def test_upsert_rejects_ragged_vectors() -> None:
    """同一个批次里维度不齐 = 「谁是谁」会错位，必须当场失败。"""
    recorder = _Recorder({})
    store = _store(recorder)
    points = [_point(size=3), _point("p1:v1:c1", size=4)]

    with pytest.raises(ValueError, match="同一批次"):
        await store.upsert(points)
    assert recorder.requests == [], "校验失败不该发出请求"
    await store.aclose()


async def test_upsert_rejects_vector_dimension_mismatch_with_collection() -> None:
    recorder = _Recorder(
        {
            ("GET", f"/collections/{COLLECTION}"): httpx.Response(
                200, json={"result": {"config": {"params": {"vectors": {"size": 8}}}}}
            )
        }
    )
    store = _store(recorder)
    await store.collection_info()

    with pytest.raises(ValueError, match="集合"):
        await store.upsert([_point(size=3)])
    await store.aclose()


async def test_search_sends_threshold_and_parses_hits() -> None:
    recorder = _Recorder(
        {
            ("POST", f"/collections/{COLLECTION}/points/search"): httpx.Response(
                200,
                json={
                    "result": [
                        {
                            "id": point_id_for("p7:v1:c0"),
                            "score": 0.83,
                            "payload": {"chunkId": "p7:v1:c0", "postId": 7, "text": "片段"},
                        }
                    ]
                },
            )
        }
    )
    store = _store(recorder)

    hits = await store.search([0.2, 0.4], top_k=5, score_threshold=0.3)

    body = json.loads(recorder.requests[-1].content)
    assert body["limit"] == 5
    assert body["score_threshold"] == 0.3, "相似度下限是 Dense 通路唯一的拒答机制"
    assert body["with_payload"] is True
    assert body["with_vector"] is False, "回传向量会让响应体积暴涨且没人用"
    assert hits[0].chunk_id == "p7:v1:c0"
    assert hits[0].post_id == 7
    assert hits[0].score == 0.83
    await store.aclose()


async def test_search_omits_threshold_when_not_given() -> None:
    recorder = _Recorder(
        {
            ("POST", f"/collections/{COLLECTION}/points/search"): httpx.Response(
                200, json={"result": []}
            )
        }
    )
    store = _store(recorder)

    hits = await store.search([0.2, 0.4], top_k=3)

    assert hits == []
    assert "score_threshold" not in json.loads(recorder.requests[-1].content)
    await store.aclose()


async def test_search_rejects_query_dimension_mismatch() -> None:
    recorder = _Recorder(
        {
            ("GET", f"/collections/{COLLECTION}"): httpx.Response(
                200, json={"result": {"config": {"params": {"vectors": {"size": 8}}}}}
            )
        }
    )
    store = _store(recorder)
    await store.collection_info()

    with pytest.raises(ValueError, match="查询向量维度"):
        await store.search([0.1, 0.2], top_k=3)
    await store.aclose()


async def test_search_result_without_payload_fails_loudly() -> None:
    """缺少 chunkId/postId 的命中无法定位引用：宁可报错也不要送出半成品。"""
    recorder = _Recorder(
        {
            ("POST", f"/collections/{COLLECTION}/points/search"): httpx.Response(
                200, json={"result": [{"id": 7, "score": 0.9, "payload": {"text": "片段"}}]}
            )
        }
    )
    store = _store(recorder)

    with pytest.raises(ProviderUnavailableError, match="chunkId/postId"):
        await store.search([0.1], top_k=3)
    await store.aclose()


async def test_delete_by_post_ids_uses_post_filter() -> None:
    recorder = _Recorder(
        {
            ("POST", f"/collections/{COLLECTION}/points/delete"): httpx.Response(
                200, json={"result": {}}
            )
        }
    )
    store = _store(recorder)

    await store.delete_by_post_ids([3, 9])

    request = recorder.requests[-1]
    assert request.url.params["wait"] == "true"
    body = json.loads(request.content)
    assert body["filter"]["must"][0]["key"] == "postId"
    assert body["filter"]["must"][0]["match"]["any"] == [3, 9]
    await store.aclose()


async def test_delete_without_ids_skips_http() -> None:
    recorder = _Recorder({})
    store = _store(recorder)

    await store.delete_by_post_ids([])

    assert recorder.requests == []
    await store.aclose()


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        (401, ProviderAuthError),
        (403, ProviderAuthError),
        (429, ProviderRateLimitError),
        (500, ProviderUnavailableError),
        (503, ProviderUnavailableError),
        (400, ProviderError),
    ],
)
async def test_http_errors_map_to_provider_taxonomy(status: int, expected: type[Exception]) -> None:
    """错误分类决定上层「重试、降级还是叫人改配置」，映射错了就会白重试或白白失败。"""
    recorder = _Recorder({("GET", "/"): httpx.Response(status, json={"status": "err"})})
    store = _store(recorder)

    with pytest.raises(expected):
        await store.health()
    await store.aclose()


async def test_timeout_and_connection_errors_are_classified() -> None:
    def timeout_handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("too slow", request=request)

    store = QdrantVectorStore(
        QdrantConfig(collection=COLLECTION),
        transport=httpx.MockTransport(timeout_handler),
    )
    with pytest.raises(ProviderTimeoutError):
        await store.health()
    await store.aclose()

    def broken_handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    store = QdrantVectorStore(
        QdrantConfig(collection=COLLECTION),
        transport=httpx.MockTransport(broken_handler),
    )
    with pytest.raises(ProviderUnavailableError):
        await store.health()
    await store.aclose()


async def test_non_json_response_is_a_loud_failure() -> None:
    """反代返回 HTML 错误页是常见现场：必须报「响应不是合法 JSON」而不是空结果。"""
    recorder = _Recorder({("GET", "/"): httpx.Response(200, text="<html>502 Bad Gateway</html>")})
    store = _store(recorder)

    with pytest.raises(ProviderUnavailableError, match="JSON"):
        await store.health()
    await store.aclose()


async def test_error_detail_is_truncated() -> None:
    """错误详情只留一小段：整页响应体进日志会把有用的信息挤掉。"""
    recorder = _Recorder(
        {("GET", "/"): httpx.Response(500, text="x" * 1000, headers={"content-type": "text/plain"})}
    )
    store = _store(recorder)

    with pytest.raises(ProviderUnavailableError) as caught:
        await store.health()

    assert caught.value.detail is not None
    assert len(caught.value.detail) <= 200
    await store.aclose()


async def test_hashes_by_post_reads_payload_without_vectors() -> None:
    """增量索引对账（M4）：只读 payload、不读向量。

    读向量的代价是「一次对账把几万条向量拉回来」—— 而对账要回答的是「有没有变」，
    不是「像不像」。
    """
    recorder = _Recorder(
        {
            ("POST", f"/collections/{COLLECTION}/points/scroll"): httpx.Response(
                200,
                json={
                    "result": {
                        "points": [
                            {"payload": {"postId": 1, "contentHash": "h1"}},
                            {"payload": {"postId": 1, "contentHash": "h2"}},
                            {"payload": {"postId": 2, "contentHash": "h3"}},
                        ],
                        "next_page_offset": None,
                    }
                },
            )
        }
    )
    store = _store(recorder)

    grouped = await store.hashes_by_post()

    assert grouped == {1: {"h1", "h2"}, 2: {"h3"}}
    body = json.loads(recorder.requests[0].content)
    assert body["with_vector"] is False, "对账不该读向量"
    assert body["with_payload"] is True
    await store.aclose()


async def test_hashes_by_post_marks_missing_hash_as_none() -> None:
    """老数据没有 `contentHash` → 记成 None（调用方据此判「要重建」），**不猜**。"""
    recorder = _Recorder(
        {
            ("POST", f"/collections/{COLLECTION}/points/scroll"): httpx.Response(
                200,
                json={
                    "result": {
                        "points": [
                            {"payload": {"postId": 7, "text": "老数据没有 contentHash"}},
                            {"payload": {"postId": 8}},
                            {"payload": {}},
                        ],
                        "next_page_offset": None,
                    }
                },
            )
        }
    )
    store = _store(recorder)

    grouped = await store.hashes_by_post()

    assert grouped[7] is None, "读不到哈希要如实标出来，而不是拿别的字段充数"
    assert grouped[8] is None
    assert 0 not in grouped, "连 postId 都没有的点直接跳过（它本身就该被清理）"
    await store.aclose()


async def test_hashes_by_post_follows_pagination() -> None:
    """滚动分页：必须跟着 next_page_offset 一直翻，否则对账只看到第一页。"""
    pages = [
        httpx.Response(
            200,
            json={
                "result": {
                    "points": [{"payload": {"postId": 1, "contentHash": "h1"}}],
                    "next_page_offset": 100,
                }
            },
        ),
        httpx.Response(
            200,
            json={
                "result": {
                    "points": [{"payload": {"postId": 2, "contentHash": "h2"}}],
                    "next_page_offset": None,
                }
            },
        ),
    ]
    calls = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["count"] += 1
        return pages[min(calls["count"] - 1, len(pages) - 1)]

    config = QdrantConfig(collection=COLLECTION)
    store = QdrantVectorStore(config, transport=httpx.MockTransport(handler))

    grouped = await store.hashes_by_post(page_size=1)

    assert set(grouped) == {1, 2}, "第二页也要读到"
    await store.aclose()
