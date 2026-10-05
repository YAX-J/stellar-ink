"""拉取模型清单的实现层测试（`app/providers/model_listing.py`）。

这一层是**唯一**真正发出站请求的地方，因此红线都在这里钉住：

1. **明文密钥只用一次**：它只出现在这次请求的 `Authorization` 头上；产物（清单/错误消息）
   里任何地方都不得出现它；
2. **不预置清单**：`fake` 回的是它自己那**一个**标识，且 `source` 是 `fake` 而不是 URL；
3. **限条数与短超时**：截断要如实（`truncated` 由「还有下一条**有效**条目」决定），
   巨型响应在读进来的时候就被上限拦住（不是读完了再截）；
4. **只取 id**：没有 id 的条目丢掉、同一个 id 去重、畸形结构给**可读原因**（不回显上游报文）；
5. **地址安全**：内网地址与查询串一律拒掉（后者顺带堵住「把密钥塞进地址」→ httpx 日志）。

网络一律用 `httpx.MockTransport` 假掉：这些用例要的是**确定的输入**，
而真实供应商的响应形状与可达性都不是测试能依赖的东西。
"""

from __future__ import annotations

import json
from collections.abc import Callable

import httpx
import pytest

from app.providers import model_listing
from app.providers.errors import (
    InvalidBaseUrlError,
    ProviderAuthError,
    ProviderRateLimitError,
    ProviderTimeoutError,
    ProviderUnavailableError,
    UnsupportedCapabilityError,
)
from app.providers.model_listing import (
    FAKE_SOURCE,
    MODEL_LIST_LIMIT,
    ProviderModel,
    fake_model_list,
    list_provider_models,
)

PUBLIC_BASE = "https://api.example.com/v1"

#: 测试用的明文密钥：它出现在**请求头**上是对的，出现在别处就是泄露
SECRET = "sk-test-plaintext-key-do-not-leak"


def _transport(handler: Callable[[httpx.Request], httpx.Response]) -> httpx.MockTransport:
    return httpx.MockTransport(handler)


def _vendor(items: list[object], *, status: int = 200) -> httpx.MockTransport:
    """假供应商：回一个 OpenAI 形状的列表（`{\"object\":\"list\",\"data\":[...]}`）。"""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json={"object": "list", "data": items})

    return _transport(handler)


async def _fetch(
    transport: httpx.AsyncBaseTransport,
    *,
    base_url: str = PUBLIC_BASE,
    api_key: str = SECRET,
    limit: int = MODEL_LIST_LIMIT,
    provider: str = "openai_compatible",
) -> model_listing.ProviderModelList:
    return await list_provider_models(
        provider=provider, base_url=base_url, api_key=api_key, transport=transport, limit=limit
    )


# ------------------------------------------------------------------ fake 协议


def test_fake_returns_its_own_single_model_not_a_vendor_list() -> None:
    """`fake` 回它自己那一个标识，**不是**任何厂商的清单。

    这条同时守着 AGENTS §5 的红线：代码里不许出现「厂商或模型名的预设清单」。
    能出现名字的地方只有 `FakeProvider.MODEL_TAG` 一处，而这里是**派生**的。
    """
    from app.providers.fake import FakeProvider

    listing = fake_model_list()

    assert [model.id for model in listing.models] == [FakeProvider.MODEL_TAG]
    assert listing.truncated is False


def test_fake_source_is_a_literal_not_a_url() -> None:
    """`source` 必须是 `fake`：我们没向任何地址发过请求，写成 URL 就是替它说谎。"""
    listing = fake_model_list()

    assert listing.source == FAKE_SOURCE
    assert "://" not in listing.source


async def test_fake_needs_no_key_and_does_not_touch_the_network() -> None:
    """`fake` 不发请求：连 transport 都不需要（给了也会被绕过）。"""

    def explode(request: httpx.Request) -> httpx.Response:  # pragma: no cover - 不该被调用
        raise AssertionError(f"fake 不该联网：{request.url}")

    listing = await _fetch(_transport(explode), api_key="", provider="fake")

    assert [model.id for model in listing.models] == ["fake"]


# ------------------------------------------------------------------ 正常路径


async def test_reads_id_and_created_and_reports_the_requested_base_url() -> None:
    listing = await _fetch(_vendor([{"id": "m-1", "created": 1730000000}, {"id": "m-2"}]))

    assert listing.models == [ProviderModel(id="m-1", created=1730000000), ProviderModel(id="m-2")]
    assert listing.truncated is False
    # source 是**实际请求的 base_url**：用户据此核对自己填得对不对
    assert listing.source == PUBLIC_BASE


async def test_requests_the_models_path_with_the_bearer_header() -> None:
    """路径由代码拼（不是让用户填完整端点），密钥只在这一行上出现一次。"""
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["authorization"] = request.headers.get("authorization", "")
        return httpx.Response(200, json={"data": [{"id": "m-1"}]})

    await _fetch(_transport(handler))

    assert seen["url"] == f"{PUBLIC_BASE}/models"
    assert seen["authorization"] == f"Bearer {SECRET}"


async def test_trailing_slash_is_normalized_into_a_single_slash() -> None:
    """用户把地址填成 `.../v1/` 时不该拼出 `//models`（有些服务会 404）。"""
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        return httpx.Response(200, json={"data": [{"id": "m-1"}]})

    listing = await _fetch(_transport(handler), base_url=f"{PUBLIC_BASE}/")

    assert seen["url"] == f"{PUBLIC_BASE}/models"
    assert listing.source == PUBLIC_BASE


async def test_missing_key_sends_no_authorization_header() -> None:
    """没有密钥就不带这个头：`Bearer `（空值）比不带头更容易被上游判成 401。"""
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["authorization"] = request.headers.get("authorization", "")
        return httpx.Response(200, json={"data": [{"id": "m-1"}]})

    await _fetch(_transport(handler), api_key="")

    assert seen["authorization"] == ""


async def test_bare_array_response_is_accepted() -> None:
    """有的服务直接回一个数组（不是 `{"data":[...]}`）—— 这两种都认，别的形状才报错。"""
    listing = await _fetch(_transport(lambda _: httpx.Response(200, json=[{"id": "m-1"}])))

    assert [model.id for model in listing.models] == ["m-1"]


async def test_empty_list_is_a_legitimate_answer_not_an_error() -> None:
    """空清单是**合法结果**（这家确实没列出来）：它必须与「拉取失败」分得开。"""
    listing = await _fetch(_vendor([]))

    assert listing.models == []
    assert listing.truncated is False


# ------------------------------------------------------------- 形状未知的输入


async def test_entries_without_id_are_dropped() -> None:
    listing = await _fetch(
        _vendor([{"id": "m-1"}, {"name": "没有 id"}, "m-2", {"id": "   "}, {"id": 42}])
    )

    assert [model.id for model in listing.models] == ["m-1"]


async def test_duplicate_ids_are_kept_once() -> None:
    """同一个 id 出现两次，下拉框里就是两条一模一样的选项 —— 看起来像面板坏了。"""
    listing = await _fetch(_vendor([{"id": "m-1"}, {"id": "m-1"}, {"id": "m-2"}, {"id": "m-2"}]))

    assert [model.id for model in listing.models] == ["m-1", "m-2"]
    assert listing.truncated is False, "去重后的条目数没超上限，就不该报截断"


async def test_items_present_but_all_without_id_is_a_readable_failure() -> None:
    """条目存在却一条都没留下 = 结构不是我们认的那个（不是「这家没有模型」）。"""
    with pytest.raises(ProviderUnavailableError, match="没有任何带 id 的条目"):
        await _fetch(_vendor([{"name": "a"}, {"name": "b"}]))


async def test_malformed_json_is_a_readable_failure() -> None:
    with pytest.raises(ProviderUnavailableError, match="不是合法 JSON"):
        await _fetch(_transport(lambda _: httpx.Response(200, text="<html>oops</html>")))


async def test_unrecognized_structure_is_a_readable_failure() -> None:
    """形状不认识时说清「期望什么」，而不是编一个空清单冒充成功。"""
    with pytest.raises(ProviderUnavailableError, match="响应结构不认识"):
        await _fetch(_transport(lambda _: httpx.Response(200, json={"foo": 1})))


async def test_upstream_body_is_not_echoed_in_the_error() -> None:
    """畸形响应的错误里**不得回显上游报文**（它可能包含我们发过去的东西）。"""
    with pytest.raises(ProviderUnavailableError) as error:
        await _fetch(_transport(lambda _: httpx.Response(200, text="SECRET-UPSTREAM-BODY")))

    assert "SECRET-UPSTREAM-BODY" not in str(error.value)


# ------------------------------------------------------------------ 条数上限


async def test_long_list_is_truncated_and_says_so() -> None:
    """超长列表：留下前 `limit` 条，并**如实**说被截断了（不是假装全都在这里）。"""
    listing = await _fetch(_vendor([{"id": f"m-{index}"} for index in range(250)]))

    assert len(listing.models) == MODEL_LIST_LIMIT
    assert listing.models[0].id == "m-0"
    assert listing.truncated is True


async def test_exactly_at_the_limit_is_not_truncated() -> None:
    """正好等于上限时没有丢东西，`truncated` 必须是 false —— 否则界面会误报「还有更多」。"""
    listing = await _fetch(_vendor([{"id": f"m-{index}"} for index in range(3)]), limit=3)

    assert len(listing.models) == 3
    assert listing.truncated is False


async def test_oversized_body_is_stopped_while_reading(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """巨型响应在读进来的时候就被拦住（不是先收进内存再谈限条数）。"""
    monkeypatch.setattr(model_listing, "MAX_BODY_BYTES", 64)

    with pytest.raises(ProviderUnavailableError, match="响应过大"):
        await _fetch(_transport(lambda _: httpx.Response(200, content=b"x" * 4096)))


# --------------------------------------------------------------- 失败分档


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        (401, ProviderAuthError),
        (403, ProviderAuthError),
        (429, ProviderRateLimitError),
        (500, ProviderUnavailableError),
        (503, ProviderUnavailableError),
        # 不提供 /models 的地址：请求本身没错，要用户去核对地址
        (404, ProviderUnavailableError),
        (405, ProviderUnavailableError),
        (400, ProviderUnavailableError),
    ],
)
async def test_status_codes_map_to_the_same_tiers_as_chat(
    status: int, expected: type[Exception]
) -> None:
    """分档与 `openai_compatible._error_for_status` 完全一致（不在这里另造一套）。"""
    with pytest.raises(expected):
        await _fetch(_transport(lambda _: httpx.Response(status, json={"error": "nope"})))


async def test_not_found_points_at_the_base_url() -> None:
    """404 的提示要指向「地址填错了」，因为那正是用户唯一能做的事。"""
    with pytest.raises(ProviderUnavailableError) as error:
        await _fetch(_transport(lambda _: httpx.Response(404)))

    assert "baseUrl" in str(error.value)


async def test_connection_failure_is_a_readable_502_class_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    with pytest.raises(ProviderUnavailableError, match="无法连接模型服务"):
        await _fetch(_transport(handler))


async def test_timeout_is_reported_as_a_timeout_not_a_generic_failure() -> None:
    """超时要与「连不上」分开：前者是等一下可能就好，后者是地址不对。"""

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("too slow", request=request)

    with pytest.raises(ProviderTimeoutError, match="超时"):
        await _fetch(_transport(handler))


async def test_unknown_provider_is_a_parameter_problem() -> None:
    """未知协议是**参数问题**（映射成 400），消息里要列出可选值。"""
    with pytest.raises(UnsupportedCapabilityError) as error:
        await _fetch(_vendor([]), provider="anthropic")

    assert "anthropic" in str(error.value)
    assert "openai_compatible" in str(error.value)
    assert "fake" in str(error.value)


# ------------------------------------------------------------------ 地址安全


@pytest.mark.parametrize(
    "base_url",
    [
        "http://127.0.0.1:8000/v1",
        "http://10.0.0.5:8080/v1",
        "http://169.254.169.254/latest/meta-data",
        "http://localhost:11434/v1",
    ],
)
async def test_private_addresses_are_rejected(base_url: str) -> None:
    """地址来自浏览器表单（任何登录用户都能填）→ 与个人配置同一档：只允许公网。"""
    with pytest.raises(InvalidBaseUrlError):
        await _fetch(_vendor([{"id": "m-1"}]), base_url=base_url)


@pytest.mark.parametrize(
    "base_url",
    [
        "https://api.example.com/v1?key=sk-x",
        "https://api.example.com/v1#frag",
    ],
)
async def test_query_string_and_fragment_are_rejected(base_url: str) -> None:
    """查询串/锚点一律拒：`/models` 要拼在地址后面，而且它可能是「把密钥塞进 URL」那条路。

    httpx 会在 INFO 级别打印请求行，所以这条拒绝对「明文密钥不进日志」是有意义的。
    """
    with pytest.raises(InvalidBaseUrlError, match="查询串"):
        await _fetch(_vendor([{"id": "m-1"}]), base_url=base_url)


# ------------------------------------------------------------- 密钥不泄露


async def test_plaintext_key_never_appears_in_the_result() -> None:
    """产物（清单 + source）里任何地方都不得出现密钥 —— 连掩码都不给。"""
    listing = await _fetch(_vendor([{"id": "m-1", "created": 1}]))

    rendered = json.dumps(
        {
            "models": [{"id": model.id, "created": model.created} for model in listing.models],
            "truncated": listing.truncated,
            "source": listing.source,
        },
        ensure_ascii=False,
    )
    assert SECRET not in rendered
    assert "sk-" not in rendered


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        (401, ProviderAuthError),
        (404, ProviderUnavailableError),
        (500, ProviderUnavailableError),
    ],
)
async def test_plaintext_key_never_appears_in_error_messages(
    status: int, expected: type[Exception]
) -> None:
    """失败消息也不能带密钥：它会被写进日志、并被原样交给用户。"""
    with pytest.raises(expected) as error:
        await _fetch(_transport(lambda _: httpx.Response(status, json={})))

    assert SECRET not in str(error.value)
