"""Qdrant 适配层：只走 HTTP，把切块与向量写进集合、按向量检索。

为什么不用官方 `qdrant-client`：它会连「传输细节 + 重试策略 + 本地 `:memory:` 模式」一起带进来。
本项目已定「向量库只用 Qdrant」，一个能悄悄退化成内存索引的客户端会让「检索质量」的结论失效；
而薄薄一层 HTTP 客户端加上 `httpx.MockTransport`，就能在**不起容器**的前提下把协议钉死，
等真实 Qdrant 打通后只需换 base_url。

⚠️ 端点选择：这里用 `/points/search`（Qdrant 1.10 起标记弃用、改推 `/points/query`）。
本项目的 Qdrant 固定在 `v1.12.4`（见 `deploy/docker/docker-compose.yml`），两者都可用；
将来升级只需改 `_SEARCH_PATH` 与响应解析两处 —— 所有路径都收在模块常量里。

关于连接方式：生产编排里 Qdrant 只绑在宿主机 `127.0.0.1:6333`，局域网/LAN 不可直达，
开发机要连就开 SSH 隧道（`ssh -L 6333:127.0.0.1:6333 <server>`），因此默认 base_url 就是
`http://127.0.0.1:6333`。这也是「等 Qdrant 连接方式确认」的答案：**隧道 + 默认端口**。
"""

from __future__ import annotations

import hashlib
import os
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

import httpx

from app.providers.errors import (
    ProviderAuthError,
    ProviderError,
    ProviderRateLimitError,
    ProviderTimeoutError,
    ProviderUnavailableError,
)

if TYPE_CHECKING:  # 只用于类型标注：运行期不 import，保持这一层可独立使用
    from app.rag.pipeline import IndexedChunk

#: 所有端点集中在这里：升级 Qdrant 时只改这几行
_COLLECTIONS = "/collections"
_SEARCH_PATH = "/points/search"
_UPSERT_PATH = "/points"
_DELETE_PATH = "/points/delete"
_SCROLL_PATH = "/points/scroll"

#: Qdrant 的 point id 只能是 uint64 或 UUID，而 chunk_id 是 `p1:v3a1b2c4:c0` 这种字符串。
#: 取 SHA-256 前 8 字节并抹掉最高位：确定性（同一 chunk 永远同一 id，重复写入即覆盖）、
#: 又不会因为超过 2^63 而被 JSON 工具链当成负数。
_ID_MASK = 0x7FFF_FFFF_FFFF_FFFF

_ALLOWED_DISTANCES = frozenset({"Cosine", "Euclid", "Dot", "Manhattan"})

#: 环境变量名（`QdrantConfig.from_env`）。默认值一个都不变：
#: 不配就是「本机 / SSH 隧道」，配了就可以直连测试机或 Qdrant Cloud。
#: ⚠️ 用 `QDRANT_*` 而不是 `AI_QDRANT_*`：基础设施地址与应用设置分开更好辨认。
ENV_BASE_URL = "QDRANT_BASE_URL"
ENV_API_KEY = "QDRANT_API_KEY"
ENV_COLLECTION = "QDRANT_COLLECTION"


def _current_fingerprint() -> str | None:
    """当前装配的嵌入模型指纹；取不到返回 None。

    指纹只是护栏：它取不到（离线单测、Provider 未装配）时**不能**让存储层不可用，
    所以这里吞掉异常并返回 None，由调用方决定「没有记录就放行」。
    """
    try:
        from app.providers.runtime import fingerprint  # noqa: PLC0415 - 延迟导入避免环

        return fingerprint()
    except Exception:  # noqa: BLE001 - 护栏不该成为新的故障点
        return None

def point_id_for(chunk_id: str) -> int:
    """chunk_id → 稳定的 uint64 point id（幂等写入的基础）。"""
    if not chunk_id:
        raise ValueError("chunk_id 不能为空：否则无法定位与覆盖同一个点")
    digest = hashlib.sha256(chunk_id.encode("utf-8")).digest()
    value = int.from_bytes(digest[:8], "big") & _ID_MASK
    # 0 是保留值，碰撞概率可忽略但没必要留这个坑
    return value or 1


@dataclass(frozen=True, slots=True)
class QdrantConfig:
    """连接与集合配置。`api_key` 只从面板/环境读入内存，不进日志、不进指纹。"""

    base_url: str = "http://127.0.0.1:6333"
    collection: str = "stellar_ink_chunks"
    api_key: str | None = None
    timeout_ms: int = 10_000
    distance: str = "Cosine"

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> QdrantConfig:
        """按环境变量构造：`QDRANT_BASE_URL` / `QDRANT_API_KEY`。

        **默认值一个都不变**（`http://127.0.0.1:6333`）：不配就还是「本机 / SSH 隧道」那条老路，
        已有部署不会因为这次改动换库。需要直连远端（测试机的 6333）或将来用 Qdrant Cloud 时，
        改环境变量即可，不必改代码。

        ``QDRANT_API_KEY`` 只在内存里（`describe()` 也只回 `auth: api_key|none`）。
        """
        source = env if env is not None else os.environ
        base_url = (source.get(ENV_BASE_URL) or "").strip() or "http://127.0.0.1:6333"
        api_key = (source.get(ENV_API_KEY) or "").strip() or None
        collection = (source.get(ENV_COLLECTION) or "").strip() or "stellar_ink_chunks"
        # 结尾斜杠会让拼出来的 URL 变成 `//collections/...`（Qdrant 能容忍，但日志里难看且
        # 与 describe() 的输出不一致）—— 这里统一去掉
        return cls(base_url=base_url.rstrip("/"), collection=collection, api_key=api_key)

    def __post_init__(self) -> None:
        if not self.base_url.startswith(("http://", "https://")):
            raise ValueError("base_url 必须是 http(s) 地址")
        if not self.collection.strip():
            raise ValueError("collection 不能为空")
        if self.timeout_ms <= 0:
            raise ValueError("timeout_ms 必须为正：否则请求会无限等")
        if self.distance not in _ALLOWED_DISTANCES:
            raise ValueError(f"distance 只能是 {sorted(_ALLOWED_DISTANCES)} 之一")

    def describe(self) -> dict[str, Any]:
        """可安全写日志/报给前端的摘要（**不含 api_key**）。"""
        return {
            "baseUrl": self.base_url,
            "collection": self.collection,
            "distance": self.distance,
            "timeoutMs": self.timeout_ms,
            "auth": "api_key" if self.api_key else "none",
        }


@dataclass(frozen=True, slots=True)
class VectorPoint:
    """一个待写入的点。`payload` 必须自带 chunkId/postId，检索回来才能定位引用。"""

    chunk_id: str
    post_id: int
    vector: list[float]
    payload: dict[str, Any] = field(default_factory=dict)
    #: 内容种类（`post` / `note`）。写进 payload：删除与对账都要按 `(kind, id)` 来，
    #: 否则文章 3 与笔记 3 会互相删到对方。
    kind: str = "post"

    def __post_init__(self) -> None:
        if self.post_id <= 0:
            raise ValueError("post_id 必须为正")
        if not self.vector:
            raise ValueError(f"点 {self.chunk_id} 的向量为空")

    @property
    def point_id(self) -> int:
        return point_id_for(self.chunk_id)

    def to_body(self) -> dict[str, Any]:
        payload = {
            **self.payload,
            "chunkId": self.chunk_id,
            "postId": self.post_id,
            "kind": self.kind,
        }
        return {"id": self.point_id, "vector": list(self.vector), "payload": payload}


@dataclass(frozen=True, slots=True)
class VectorHit:
    """检索结果：既给排序用的分数，也给引用定位用的 chunk_id / post_id / kind。"""

    point_id: int
    chunk_id: str
    post_id: int
    score: float
    payload: dict[str, Any] = field(default_factory=dict)
    #: 内容种类：引用据此决定跳 `/read/:id` 还是 `/note/:id`。
    #: 缺失按 `post`（笔记接入之前索引的点没有这个字段）。
    kind: str = "post"


@dataclass(frozen=True, slots=True)
class CollectionInfo:
    exists: bool
    dimension: int | None = None
    points_count: int = 0
    status: str | None = None


class QdrantVectorStore:
    """薄 HTTP 客户端：建集合、写点、按向量检索、按文章删点、健康检查。

    错误分类沿用 Provider 层：鉴权错不可重试、429/5xx/超时可重试。
    响应解析失败一律**报错而不是返回空结果** —— 空结果会被上层当成「没有依据」，
    那是拒答，不是故障；把故障说成拒答会误导产品判断。
    """

    def __init__(
        self,
        config: QdrantConfig | None = None,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._config = config or QdrantConfig()
        self._dimension: int | None = None
        self._client = client or httpx.AsyncClient(
            base_url=self._config.base_url.rstrip("/"),
            timeout=httpx.Timeout(self._config.timeout_ms / 1000),
            headers=self._headers(),
            transport=transport,
            # 内部服务不认环境代理（`HTTP_PROXY` 等），两个原因都实测过：
            # ① 本机装了代理时，发往 127.0.0.1:6333 的请求会被代理接走并回一个 502 ——
            #    表现为「Qdrant 返回 502」，把「隧道没开」误报成「对面报错」；
            # ② 更要紧的是 `api-key` 会跟着请求进代理，内网密钥不该离开这台机器。
            # 对比：`providers/openai_compatible.py` 保持默认（外部 API 可能**需要**代理才能到达）。
            trust_env=False,
        )

    @property
    def config(self) -> QdrantConfig:
        return self._config

    @property
    def dimension(self) -> int | None:
        """已知的集合维度（`ensure_collection` / `collection_info` 之后才有值）。"""
        return self._dimension

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self._config.api_key:
            headers["api-key"] = self._config.api_key
        return headers

    async def aclose(self) -> None:
        await self._client.aclose()

    def point(self, chunk: IndexedChunk, vector: Sequence[float]) -> VectorPoint:
        """把「切块 + 向量」装成待写入的点（索引管道的 `point_factory`）。

        payload 直接沿用 `Chunk.to_payload()`（锚点、章节路径、内容哈希都在里面），
        不在这里另写一套字段：写库与引用定位必须是同一份元数据。

        另外补一个 `modelFingerprint`：**这条向量是哪个嵌入模型建的**。
        没有它，换模型之后检索不会报错 —— 只会静静地返回错的结果
        （向量不在同一空间，相似度毫无意义），那是「链路全对、结果全错」里最难查的一种。
        **取不到指纹就不写这个字段**（例如离线单测里没有装配 Provider）：护栏不该
        把「能不能写库」也一起挡掉。
        """
        payload = dict(chunk.payload)
        current = _current_fingerprint()
        if current:
            payload["modelFingerprint"] = current
        return VectorPoint(
            chunk_id=chunk.chunk_id,
            post_id=chunk.post_id,
            vector=list(vector),
            payload=payload,
            kind=chunk.kind,
        )

    async def sample_model_fingerprint(self) -> str | None:
        """随便取一条已索引的点，读出它的 `modelFingerprint`。

        :return 指纹；**集合还不存在、或索引为空时返回 None** —— 那是「还没建」，
            不是「不一致」。⚠️ 集合不存在必须走"还没有索引"这条路而不是抛 404：
            首次开向量库时索引本来就是空的，而这里的异常会一路冒成 500，
            把「还没建索引」说成「服务坏了」。
        """
        response = await self._send(
            "POST",
            f"{_COLLECTIONS}/{self._config.collection}{_SCROLL_PATH}",
            json={"limit": 1, "with_payload": True, "with_vector": False},
        )
        if response.status_code == 404:  # 集合不存在 = 还没建索引（与 collection_info 同口径）
            return None
        payload = self._decode(response)
        points = payload.get("result", {}).get("points", [])
        if not points:
            return None
        return points[0].get("payload", {}).get("modelFingerprint") or None

    async def assert_model_fingerprint(self, expected: str | None = None) -> str | None:
        """校验「库里的向量是不是当前这个嵌入模型建的」。

        - 索引为空 / 旧数据没有这个字段 → 放行（`None`）：不能因为「没记录」就挡住写入；
        - 有记录且与当前不一致 → **抛错**，让人去重建索引，而不是拿错的结果回答用户。

        :raises ProviderError: 指纹不一致（换模型必须整库重建）
        """
        from app.providers.errors import ProviderError  # noqa: PLC0415 - 同上的延迟导入

        want = expected if expected is not None else _current_fingerprint()
        actual = await self.sample_model_fingerprint()
        if actual is None or want is None or actual == want:
            return actual
        raise ProviderError(
            f"索引里的向量是用另一个嵌入模型建的（库里 {actual} / 当前 {want}）："
            "换嵌入模型必须整库重建，否则检索结果没有意义"
            "（调 POST /admin/index/rebuild 全量重建，或 ai-service 的 /ai/admin/index/rebuild）"
        )

    async def health(self) -> dict[str, Any]:
        """`GET /` 返回版本信息；只取白名单字段，避免把整个响应塞进日志。"""
        payload = await self._request("GET", "/")
        return {
            "title": str(payload.get("title", "")),
            "version": str(payload.get("version", "")),
        }

    async def collection_info(self) -> CollectionInfo:
        """集合不存在时返回 `exists=False`，而不是抛错 —— 首次建索引是正常路径。"""
        response = await self._send("GET", f"{_COLLECTIONS}/{self._config.collection}")
        if response.status_code == 404:
            self._dimension = None
            return CollectionInfo(exists=False)
        payload = self._decode(response)
        result = payload.get("result") or {}
        dimension = _dimension_of(result)
        self._dimension = dimension
        return CollectionInfo(
            exists=True,
            dimension=dimension,
            points_count=int(result.get("points_count") or 0),
            status=result.get("status"),
        )

    async def ensure_collection(self, *, dimension: int, recreate: bool = False) -> CollectionInfo:
        """确保集合存在且**维度一致**。

        维度不一致必须报错而不是自动重建：那意味着索引与查询用的不是同一个嵌入模型，
        悄悄重建等于把旧向量全删掉，而调用方还以为一切正常。
        """
        if dimension <= 0:
            raise ValueError("dimension 必须为正")
        info = await self.collection_info()
        if info.exists and recreate:
            await self.delete_collection()
            info = CollectionInfo(exists=False)
        if info.exists:
            if info.dimension is not None and info.dimension != dimension:
                raise ValueError(
                    f"集合 {self._config.collection} 维度是 {info.dimension}，"
                    f"而当前嵌入模型是 {dimension} 维：必须换集合名或显式 recreate"
                )
            return info

        body = {"vectors": {"size": dimension, "distance": self._config.distance}}
        await self._request("PUT", f"{_COLLECTIONS}/{self._config.collection}", json=body)
        self._dimension = dimension
        return CollectionInfo(exists=True, dimension=dimension)

    async def upsert(self, points: Sequence[VectorPoint]) -> int:
        """幂等写入（同一 chunk_id 覆盖同一个点）：索引可以安全重跑。"""
        if not points:
            return 0
        size = len(points[0].vector)
        known = self._dimension
        if known is not None and size != known:
            raise ValueError(
                f"向量维度 {size} 与集合 {self._config.collection} 的 {known} 不一致："
                "换了嵌入模型就必须换集合或重建索引"
            )
        for point in points:
            if len(point.vector) != size:
                # 批次内维度不齐会让「谁是谁」彻底错位，必须当场失败
                raise ValueError(
                    f"点 {point.chunk_id} 的向量维度 {len(point.vector)}与同一批次的 {size} 不一致"
                )
        body = {"points": [point.to_body() for point in points]}
        response = await self._send(
            "PUT",
            f"{_COLLECTIONS}/{self._config.collection}{_UPSERT_PATH}",
            params={"wait": "true"},
            json=body,
        )
        self._decode(response)
        return len(points)

    async def search(
        self,
        vector: Sequence[float],
        *,
        top_k: int = 10,
        score_threshold: float | None = None,
    ) -> list[VectorHit]:
        """按向量检索。`score_threshold` 是**拒答机制**：全被挡掉即返回空列表。"""
        if top_k <= 0:
            raise ValueError("top_k 必须为正")
        if not vector:
            raise ValueError("查询向量不能为空")
        if self._dimension is not None and len(vector) != self._dimension:
            raise ValueError(f"查询向量维度 {len(vector)} 与集合维度 {self._dimension} 不一致")
        body: dict[str, Any] = {
            "vector": list(vector),
            "limit": top_k,
            "with_payload": True,
            "with_vector": False,
        }
        if score_threshold is not None:
            body["score_threshold"] = score_threshold
        payload = await self._request(
            "POST", f"{_COLLECTIONS}/{self._config.collection}{_SEARCH_PATH}", json=body
        )
        results = payload.get("result")
        if not isinstance(results, list):
            raise ProviderUnavailableError("Qdrant 检索响应缺少 result 数组")
        return [_hit_from_row(row) for row in results]

    async def hashes_by_docs(
        self, *, page_size: int = 256, max_points: int = 200_000
    ) -> dict[tuple[str, int], set[str] | None]:
        """把索引里**已有点的段落哈希**按文档读回来（增量索引对账用，M4）。

        文档标识是 `(kind, id)`：文章 3 与笔记 3 是两个文档，按数字 id 归并会让
        「重建文章」把笔记的那份哈希当成自己的，进而漏掉真正该重建的那一篇。

        三条设计：

        * **只读 payload、不读向量**（`with_vector=false`）：对账要回答的是「有没有变」，
          不是「像不像」—— 读向量会让一次对账把几万条向量拉回来。
        * **滚动分页**：Qdrant 的 scroll 用 `next_page_offset` 翻页；这里按
          `page_size` 一直翻到没有下一页，并有 `max_points` 上限防止集合大到把内存吃光
          （到上限就停，宁可这次对账不完整也不要 OOM —— 对账是幂等的，再跑一次即可）。
        * **payload 没有 `contentHash` 的文档记成 `None`**：调用方据此判定「要重建」。
          这里**不猜**（比如拿 chunkId 充数），因为猜错的后果是改动永远进不了索引。
        """
        grouped: dict[tuple[str, int], set[str]] = {}
        offset: Any = None
        scanned = 0
        while True:
            body: dict[str, Any] = {
                "limit": page_size,
                "with_payload": True,
                "with_vector": False,
            }
            if offset is not None:
                body["offset"] = offset
            payload = await self._request(
                "POST",
                f"{_COLLECTIONS}/{self._config.collection}{_SCROLL_PATH}",
                json=body,
            )
            result = payload.get("result")
            if not isinstance(result, dict):
                raise ProviderUnavailableError("Qdrant 滚动响应缺少 result 对象")
            points = result.get("points")
            if not isinstance(points, list):
                raise ProviderUnavailableError("Qdrant 滚动响应缺少 points 数组")
            for row in points:
                if not isinstance(row, dict):
                    continue
                scanned += 1
                point_payload = row.get("payload")
                if not isinstance(point_payload, dict):
                    continue
                post_id = point_payload.get("postId")
                if not isinstance(post_id, int):
                    # 连 postId 都没有的点：不知道属于哪篇，跳过（它本身就该被清理）
                    continue
                key = (_kind_of_payload(point_payload), post_id)
                content_hash = point_payload.get("contentHash")
                if isinstance(content_hash, str) and content_hash:
                    grouped.setdefault(key, set()).add(content_hash)
                else:
                    # 显式记成 None：调用方会按「要重建」处理（见方法 docstring）
                    grouped.setdefault(key, set())
            offset = result.get("next_page_offset")
            if offset is None or scanned >= max_points:
                break
        # 空集合的文档换成 None，让「读不到哈希」与「哈希为空」在调用方看来是同一件事
        return {key: (hashes or None) for key, hashes in grouped.items()}

    async def delete_by_docs(self, keys: Sequence[tuple[str, int]]) -> None:
        """按**文档标识** `(kind, id)` 删点：内容改动 / 删除 / 转为不可见后重建索引时用。

        为什么不能只按 `postId` 删：文章 3 与笔记 3 是两个文档，只按数字 id 删，
        重建笔记会顺手删掉同号文章，而日志里什么都看不出来。

        ⚠️ 文章的过滤要**额外带上「kind 字段缺失」这一支**：笔记接入之前索引的点没有
        `kind` 字段，而 Qdrant 对**不存在的 payload 字段做匹配是一条都不命中的** ——
        少了这一支，升级后第一次重建就删不掉任何旧点，旧片段会继续被检索、引用指向旧正文。
        """
        grouped: dict[str, set[int]] = {}
        for kind, content_id in keys:
            grouped.setdefault(kind, set()).add(int(content_id))
        if not grouped:
            return
        clauses: list[dict[str, Any]] = []
        for kind, ids in sorted(grouped.items()):
            if kind == "post":
                clauses.append(
                    {
                        "must": [
                            {"key": "postId", "match": {"any": sorted(ids)}},
                            {
                                "should": [
                                    {"key": "kind", "match": {"value": "post"}},
                                    {"is_empty": {"key": "kind"}},
                                ]
                            },
                        ]
                    }
                )
            else:
                clauses.append(
                    {
                        "must": [
                            {"key": "kind", "match": {"value": kind}},
                            {"key": "postId", "match": {"any": sorted(ids)}},
                        ]
                    }
                )
        await self._request(
            "POST",
            f"{_COLLECTIONS}/{self._config.collection}{_DELETE_PATH}",
            params={"wait": "true"},
            json={"filter": {"should": clauses}},
        )

    async def delete_by_post_ids(self, post_ids: Sequence[int]) -> None:
        """按**文章**数字 id 删点（便捷入口，等价于 `delete_by_docs([("post", id), …])`）。"""
        await self.delete_by_docs([("post", int(post_id)) for post_id in post_ids])

    async def delete_collection(self) -> None:
        """删除整个集合（重建索引、或冒烟脚本收尾时用）。

        这是**破坏性**操作：调用方必须自己清楚「为什么可以删」。`ensure_collection`
        只在显式 `recreate=True` 时才走这里 —— 维度不一致时宁可报错也不自动重建。
        """
        await self._request("DELETE", f"{_COLLECTIONS}/{self._config.collection}")
        self._dimension = None

    async def _send(self, method: str, url: str, **kwargs: Any) -> httpx.Response:
        """唯一发请求的地方：把 httpx 的异常翻译成 Provider 层分类。"""
        try:
            return await self._client.request(method, url, **kwargs)
        except httpx.TimeoutException as exc:
            raise ProviderTimeoutError("连接 Qdrant 超时", detail=str(exc)) from exc
        except httpx.HTTPError as exc:
            raise ProviderUnavailableError("无法连接 Qdrant", detail=str(exc)) from exc

    async def _request(self, method: str, url: str, **kwargs: Any) -> dict[str, Any]:
        response = await self._send(method, url, **kwargs)
        return self._decode(response)

    def _decode(self, response: httpx.Response) -> dict[str, Any]:
        if response.status_code in (401, 403):
            raise ProviderAuthError("Qdrant 拒绝了请求：检查 api-key")
        if response.status_code == 429:
            raise ProviderRateLimitError("Qdrant 限流（429）")
        if response.status_code >= 500:
            raise ProviderUnavailableError(
                f"Qdrant 返回 {response.status_code}", detail=_snippet(response)
            )
        if response.status_code >= 400:
            # 其余 4xx 是「参数/状态不对」：重试无用，但要说清是什么（不冒充上游故障）
            raise ProviderError(f"Qdrant 返回 {response.status_code}", detail=_snippet(response))
        try:
            payload = response.json()
        except ValueError as exc:
            raise ProviderUnavailableError(
                "Qdrant 响应不是合法 JSON", detail=_snippet(response)
            ) from exc
        if not isinstance(payload, dict):
            raise ProviderUnavailableError("Qdrant 响应不是 JSON 对象")
        return payload


def _hit_from_row(row: Any) -> VectorHit:
    if not isinstance(row, dict):
        raise ProviderUnavailableError("Qdrant 检索结果里出现了非对象元素")
    payload = row.get("payload") or {}
    chunk_id = payload.get("chunkId")
    post_id = payload.get("postId")
    if not chunk_id or post_id is None:
        # 少了这两个字段就无法定位引用，宁可在这一层失败也不要把半成品往上送
        raise ProviderUnavailableError("Qdrant 命中缺少 chunkId/postId，无法定位引用")
    return VectorHit(
        point_id=int(row.get("id", 0)),
        chunk_id=str(chunk_id),
        post_id=int(post_id),
        score=float(row.get("score", 0.0)),
        payload=payload,
        kind=_kind_of_payload(payload),
    )


def _kind_of_payload(payload: Any) -> str:
    """payload 里的 `kind`；缺失或非法按 `post`。

    缺失是**正常的**：笔记接入之前索引的点没有这个字段，而它们全是文章。
    """
    if isinstance(payload, dict):
        kind = payload.get("kind")
        if isinstance(kind, str) and kind in {"post", "note"}:
            return kind
    return "post"


def _dimension_of(result: dict[str, Any]) -> int | None:
    vectors = (result.get("config") or {}).get("params", {}).get("vectors")
    if isinstance(vectors, dict) and isinstance(vectors.get("size"), int):
        return int(vectors["size"])
    # 命名向量形态：{name: {size: ...}}
    if isinstance(vectors, dict):
        sizes = [
            int(value["size"])
            for value in vectors.values()
            if isinstance(value, dict) and isinstance(value.get("size"), int)
        ]
        if len(set(sizes)) == 1 and sizes:
            return sizes[0]
    return None


def _snippet(response: httpx.Response) -> str:
    """错误详情只留一小段响应体，避免把整页 HTML 塞进日志。"""
    text = response.text.strip().replace("\n", " ")
    return text[:200]
