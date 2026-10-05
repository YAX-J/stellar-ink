"""从供应商**实时**拉取模型清单（OpenAI 兼容协议的标准端点 `{base_url}/models`）。

面板里的「添加模型」表单此前要手打模型名。可选清单**只能**来自供应商自己返回的这一份：
AGENTS §5 定死「面板是模型的唯一来源，代码里没有任何默认模型或厂商预设」——
在代码里预置一份厂商清单，表现会是「面板里明明有这个模型，一填就 404」
（厂商下架/改名时没人会记得回来改代码）。

六条刻意保持的口径（每条都被一条单测盯着）：

1. **明文密钥只用一次**：它只作为这一次请求的 `Authorization` 头出现，不落库、不进日志、
   不进审计、不进响应（连掩码都不回）。⚠️ 本模块**连 `base_url` 都不写日志**：
   密钥可能被人塞进地址的路径里，而 httpx 会在 INFO 级别打印请求行 ——
   少打一处地址，就少一条「密钥进了日志」的路。查询串与锚点则直接拒掉（见
   `_normalized_base_url`），因为 `/models` 要拼在它后面，带查询串一定是填错了。
2. **限条数**（`MODEL_LIST_LIMIT`，理由见该常量）：供应商可能返回上千条，而这是一个下拉框。
   截断了就如实回 `truncated=True`，不假装「全都在这里」。
3. **短超时**（5 秒，连接 3 秒）：拉列表是辅助信息，用户正盯着按钮等；
   用对话那档 30 秒（`ProviderConfig.timeout_ms`）在这里是不可接受的。
4. **只取 `id`**：这是**形状未知的外部输入**，多取一个字段就多一份「它变了我们就崩」的风险。
   没有可用 `id` 的条目直接丢掉；丢光了（条目存在但一条都没留下）才判为结构异常。
5. **失败不改变任何已保存配置**：本模块只发 `GET`，一个字都不写 ——
   辅助信息失败不得损伤主流程（拉不到清单不影响已经存好的模型配置）。
6. **`fake` 不装作拉了远端**：它回自己那**一个**标识，且 `source` 是 `fake` 而不是一个 URL。
   理由见 `fake_model_list`。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field

import httpx

from app.providers.errors import (
    InvalidBaseUrlError,
    ProviderAuthError,
    ProviderError,
    ProviderRateLimitError,
    ProviderTimeoutError,
    ProviderUnavailableError,
    UnsupportedCapabilityError,
)
from app.providers.fake import FakeProvider
from app.providers.url_policy import check_base_url

#: OpenAI 兼容协议里「列模型」的路径：拼在 `base_url` 后面（与 `/chat/completions` 同一口径）。
#: ⚠️ 代码拼路径而不是让用户填完整端点：填成完整端点会拼出 `/models/models`，
#: 而报出来的话是「地址不对」—— 与 `/rerank/rerank` 那个坑是同一类。
MODELS_PATH = "/models"

#: 条数上限。定 200 的理由：
#: - 常见供应商（DeepSeek 2 条、OpenAI ~60 条、硅基流动 ~100 条）都在上限内，正常路径不受影响；
#: - 聚合型供应商（OpenRouter 等）会返回 300+ 条，那时截断并由 `truncated` **如实说明**，
#:   界面据此提示「还有更多，请直接填完整模型名」；
#: - 界面上限也有意义：几百条的 `<select>` 谁都用不了，而请求/响应都还是个 JSON。
MODEL_LIST_LIMIT = 200

#: 读取超时：拉列表是辅助信息，5 秒足够（正常供应商 <1s 返回），但不会让用户干等 30 秒。
MODELS_READ_TIMEOUT_SECONDS = 5.0
#: 连接超时：连不上要**很快**失败 —— 用户填错域名时没有必要等满读取超时。
MODELS_CONNECT_TIMEOUT_SECONDS = 3.0

#: 响应体上限：条数上限管的是「留下多少」，这里管的是「读进来多少」。
#: 没有它，一个畸形/恶意的巨大响应会先把内存吃掉再谈截断。
MAX_BODY_BYTES = 4 * 1024 * 1024

#: 支持的协议实现（与 `ProviderRegistry._build` 认的那两个逐字一致）。
SUPPORTED_PROVIDERS = frozenset({"openai_compatible", "fake"})

#: `fake` 的 `source`：**刻意不是一个 URL** —— 我们没有向任何地址发过请求，
#: 把用户填的（或空的）地址写在这里等于替它说「这个地址上有这些模型」。
FAKE_SOURCE = "fake"


@dataclass(frozen=True, slots=True)
class ProviderModel:
    """供应商返回的一个模型条目：**只留 id**（外加可选的时间戳）。"""

    id: str
    created: int | None = None


@dataclass(frozen=True, slots=True)
class ProviderModelList:
    """一次拉取的结果。

    `source` 是**实际请求的 `base_url`**（绝不含密钥），回给用户是为了让他核对自己填得对不对 ——
    「拉到了 5 个模型」这句话，只有在同时知道「从哪儿拉的」之后才有意义。
    """

    models: list[ProviderModel] = field(default_factory=list)
    truncated: bool = False
    source: str = FAKE_SOURCE


def fake_model_list() -> ProviderModelList:
    """`fake` 协议的模型清单：它自己那**一个**标识，不含任何厂商与模型名。

    为什么不是 400「fake 没有清单」：协议选成 fake 时用户仍然要填一个模型名，
    而 Fake 认的就是 `FakeProvider.MODEL_TAG` —— 把它回给用户是**如实**的。
    为什么不是编一份看起来像厂商的清单：那正是红线 §5 要拦的事（预置清单）。
    `source=fake` 让界面不会显示成「已从某地址拉到」—— 那是这句诚实的关键。

    ⚠️ 标识从 `FakeProvider.MODEL_TAG` **派生**，不在这里另写一份字面量：
    将来 fake 改了标识名，这里跟着变，不会留下一个指向不存在模型的选项。
    """
    return ProviderModelList(
        models=[ProviderModel(id=FakeProvider.MODEL_TAG)],
        truncated=False,
        source=FAKE_SOURCE,
    )


async def list_provider_models(
    *,
    provider: str,
    base_url: str,
    api_key: str = "",
    transport: httpx.AsyncBaseTransport | None = None,
    limit: int = MODEL_LIST_LIMIT,
) -> ProviderModelList:
    """按协议取模型清单（`fake` 不发请求，其余走 `{base_url}/models`）。

    :param api_key: 明文密钥，**只为这一次请求存在**（调用方不得缓存/记录它）
    :param transport: 测试注入的 httpx transport（单测不打真实网络）
    :raises UnsupportedCapabilityError: 未知协议（→ 400，参数问题）
    :raises InvalidBaseUrlError: 地址不过安全策略（→ 400，让用户去改地址）
    :raises ProviderAuthError: 密钥无效（→ 401）
    :raises ProviderRateLimitError: 被限流（→ 429）
    :raises ProviderTimeoutError / ProviderUnavailableError: 超时、连不上、不支持 /models（→ 502）
    """
    if provider == "fake":
        return fake_model_list()
    if provider != "openai_compatible":
        # 未知协议是**参数问题**（400），不是上游故障（502）——
        # 消息与 `ProviderRegistry._build` 同一口径，避免两处说法不一致
        raise UnsupportedCapabilityError(
            f"未知的 provider 实现：{provider}",
            detail="当前支持 " + " 与 ".join(sorted(SUPPORTED_PROVIDERS)),
        )

    source = _normalized_base_url(base_url)
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    timeout = httpx.Timeout(
        MODELS_READ_TIMEOUT_SECONDS, connect=MODELS_CONNECT_TIMEOUT_SECONDS
    )

    try:
        async with httpx.AsyncClient(
            timeout=timeout, transport=transport, headers=headers
        ) as client:
            async with client.stream("GET", f"{source}{MODELS_PATH}") as response:
                if response.status_code >= 400:
                    raise _error_for_status(response.status_code)
                raw = await _read_capped(response)
    except httpx.TimeoutException as error:
        raise ProviderTimeoutError(
            f"拉取模型列表超时（{MODELS_READ_TIMEOUT_SECONDS:.0f} 秒）",
            detail="该地址可能不可达，或它不提供 /models 端点",
        ) from error
    except httpx.HTTPError as error:
        raise ProviderUnavailableError(
            "无法连接模型服务", detail="核对地址与网络（内网地址在个人配置里是被拒的）"
        ) from error

    return _parse_list(raw, source=source, limit=limit)


def _normalized_base_url(base_url: str) -> str:
    """校验并归一化地址，返回 `source`（去尾斜杠）。

    两件事：

    * **过 `url_policy`（只允许公网）**：地址是**服务端**拿去发请求的，而这个地址来自
      浏览器表单（任何登录用户都能填）—— 不校验就是开放 SSRF。许可口径与
      `config_source` 里「用户那份」逐字一致（站长那份才允许内网）。
    * **拒掉查询串/锚点**：`/models` 要拼在地址后面，带查询串一定是填错；
      顺带掐掉「把密钥塞进查询串」这条路（httpx 会 INFO 打印请求行）。
    """
    raw = (base_url or "").strip()
    check_base_url(raw, allow_private=False)
    if "?" in raw or "#" in raw:
        raise InvalidBaseUrlError(
            "接口地址不要带查询串或锚点",
            detail="/models 会拼在它后面；请只填 API 根地址，例如 https://api.example.com/v1",
        )
    return raw.rstrip("/")


async def _read_capped(response: httpx.Response) -> bytes:
    """读响应体，超过 `MAX_BODY_BYTES` 立刻中止（**不把巨型响应读完**）。

    分块累加而不是 `aread()`：后者会把整个响应先收进内存，再谈「限条数」就晚了。
    """
    chunks: list[bytes] = []
    total = 0
    async for chunk in response.aiter_bytes():
        total += len(chunk)
        if total > MAX_BODY_BYTES:
            raise ProviderUnavailableError(
                "模型列表响应过大",
                detail=f"超过 {MAX_BODY_BYTES // (1024 * 1024)} MiB 上限，已中止读取",
            )
        chunks.append(chunk)
    return b"".join(chunks)


def _parse_list(raw: bytes, *, source: str, limit: int) -> ProviderModelList:
    """把响应体变成清单；形状不认识就给**可读原因**，不回显上游报文。"""
    try:
        payload: object = json.loads(raw)
    except ValueError as error:
        raise ProviderUnavailableError(
            "模型列表不是合法 JSON",
            detail="该地址可能不是 OpenAI 兼容的 /models 端点",
        ) from error

    items = _items_of(payload)
    models, truncated = _entries_of(items, limit=limit)
    if not models and items:
        # 条目存在却一条都没留下 = 结构不是我们认的那个（不是「这家没有模型」）
        raise ProviderUnavailableError(
            "模型列表里没有任何带 id 的条目",
            detail="期望每条形如 {\"id\": \"模型名\"}",
        )
    return ProviderModelList(models=models, truncated=truncated, source=source)


def _items_of(payload: object) -> list[object]:
    """取出条目数组：认 OpenAI 的 `{"data": [...]}`，也认直接给一个数组的服务。

    只认这两种：把「什么都试一遍」写成解析器，只会让「填错了地址」表现成
    「这家供应商没有模型」—— 那是最难查的一类问题。
    """
    if isinstance(payload, list):
        return list(payload)
    if isinstance(payload, dict):
        data = payload.get("data")
        if isinstance(data, list):
            return list(data)
    raise ProviderUnavailableError(
        "模型列表的响应结构不认识",
        detail='期望 {"data": [...]}（或直接一个数组）—— 该地址可能不是 /models 端点',
    )


def _entries_of(items: list[object], *, limit: int) -> tuple[list[ProviderModel], bool]:
    """条目 → 清单：只取 `id`、丢掉没有 id 的、**按 id 去重**、限条数。

    去重是刻意的：同一个 id 出现两次，下拉框里就是两条一模一样的选项，
    看起来像「面板坏了」。保留**第一次出现**的顺序（供应商给的顺序通常按其自身逻辑）。
    """
    models: list[ProviderModel] = []
    seen: set[str] = set()
    truncated = False
    for item in items:
        model = _entry_of(item)
        if model is None or model.id in seen:
            continue
        if len(models) >= limit:
            # 截断由「还有下一条**有效**条目」决定，不是由「原数组更长」决定：
            # 数出来才对（多出来的全是重复项时并没有真的截断任何东西）
            truncated = True
            break
        seen.add(model.id)
        models.append(model)
    return models, truncated


def _entry_of(item: object) -> ProviderModel | None:
    """一条条目 → `ProviderModel`；**取不到可用的 id 就返回 None**（调用方丢掉它）。"""
    if not isinstance(item, dict):
        return None
    raw_id = item.get("id")
    if not isinstance(raw_id, str) or not raw_id.strip():
        return None
    return ProviderModel(id=raw_id.strip(), created=_as_int(item.get("created")))


def _as_int(value: object) -> int | None:
    """宽松取整（`created` 有的服务给字符串/浮点，有的干脆不给）。`bool` 不算整数。"""
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float, str, bytes, bytearray)):
        try:
            return int(value)
        except (TypeError, ValueError):
            return None
    return None


def _error_for_status(status: int) -> ProviderError:
    """HTTP 状态 → 分类错误（分档与 `openai_compatible._error_for_status` 完全一致）。

    **不回显上游报文**：那可能包含我们发过去的内容。只给状态与可操作提示。

    「不支持 /models」也归 502：404/405/400 都说明这个地址**不是**我们以为的那个端点，
    但请求本身没错 —— 用户要做的是核对地址（提示里会这么说），而不是改请求参数。
    """
    if status in (401, 403):
        return ProviderAuthError(
            "模型密钥无效或无权限，请核对 API Key 是否填对（该 Key 是否允许查看模型列表）"
        )
    if status == 429:
        return ProviderRateLimitError("模型服务限流，请稍后重试")
    if status == 404:
        return ProviderUnavailableError(
            "该地址没有 /models 端点",
            detail="确认 baseUrl 填的是 API 根地址（如 https://api.example.com/v1），路径由代码拼",
        )
    if status >= 500:
        return ProviderUnavailableError(f"模型服务返回 {status}")
    return ProviderUnavailableError(
        f"模型服务拒绝了拉取清单的请求（HTTP {status}）",
        detail="该地址可能不是 OpenAI 兼容端点",
    )
