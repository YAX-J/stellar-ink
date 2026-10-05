"""模型清单端点的接口层测试（`POST /provider/models`）。

这一层要守的东西与 `/agent/verify` 那一刀同类，但多了两件**只在这一层**能验的事：

1. **密钥从哪来**：请求里给了就用它；没给就用该用户已保存的该角色密钥；两处都没有 →
   **不带 `Authorization` 头试一次**（`/models` 是否公开由供应商决定），由供应商来回答。
   断言不是「返回了什么」，而是**上游真的收到了哪把密钥**（MockTransport 里看请求头）——
   「用了正确的密钥」这件事只有在那条线上才看得见。
2. **明文密钥不进响应**：连掩码都不回（响应里干脆没有这个字段）。这条也在整段响应体上断言。
   顺带在**日志**上断言：它进了日志就再也收不回来。

⚠️ 取数走的是**真实代码路径**（拼 URL、读流、解析、失败分档），只有网络被 `MockTransport` 换掉：
把 `list_provider_models` 整个替掉的话，「端点把密钥传下去了吗」就无从验起。

`fake` 协议那一条单独盯着 AGENTS §5：清单只能来自供应商，代码里不许有预置的厂商/模型名。
"""

from __future__ import annotations

import json
from collections.abc import Callable, Sequence

import httpx
import pytest
from fastapi import FastAPI

from app.core.internal_auth import InternalRequestVerifier
from app.main import create_app
from app.providers import model_listing, runtime
from app.providers.config_source import (
    ProviderConfigError,
    load_own_user_configs,
    load_provider_configs,
)
from app.providers.models import ProviderCapabilities, ProviderConfig
from tests.signing import FIXED_NONCE, FIXED_TIMESTAMP_MS, call, load_vector, signed_headers

PUBLIC_BASE = "https://api.example.com/v1"

#: 请求里带的明文密钥：出现在**请求头**上是对的，出现在响应体或日志里就是泄露
SECRET = "sk-request-plaintext-key"
#: 已保存配置里的密钥
SAVED_SECRET = "sk-saved-plaintext-key"

RESULT_FIELDS = {"models", "truncated", "source"}
MODEL_FIELDS = {"id", "created"}

#: 假供应商的处理函数（端点会通过 httpx 调它）
VendorHandler = Callable[[httpx.Request], httpx.Response]

#: httpx 的真身：下面的 fixture 只拦「没指定 transport」的那次构造（= 端点在拉清单），
#: 不动测试自己的 ASGI 客户端（它显式带了 transport）
_REAL_ASYNC_CLIENT = httpx.AsyncClient


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    return create_app(verifier=InternalRequestVerifier(secret))


@pytest.fixture(autouse=True)
def _restore_user_source() -> object:
    """每个用例后把两个「按用户取配置」的来源还原成真实实现（它们是进程级状态）。

    两个都要还原：`_user_source` 是「覆盖 + 回落」的合并结果，
    `_own_user_source` 只含用户自己的行（借密钥那条路径用）—— 漏还原一个，
    后面的用例就可能落到真实读库上。
    """
    yield None
    runtime.use_user_config_source(lambda user_id: load_provider_configs(user_id=user_id))
    runtime.use_own_user_config_source(lambda user_id: load_own_user_configs(user_id))


@pytest.fixture()
def vendor(monkeypatch: pytest.MonkeyPatch) -> Callable[[VendorHandler], None]:
    """把端点的出站请求接到一个假供应商上（仍然走真实的取数代码路径）。"""

    def install(handler: VendorHandler) -> None:
        def factory(*args: object, **kwargs: object) -> httpx.AsyncClient:
            if kwargs.get("transport") is None:
                kwargs["transport"] = httpx.MockTransport(handler)
            return _REAL_ASYNC_CLIENT(*args, **kwargs)  # type: ignore[arg-type]

        monkeypatch.setattr(model_listing.httpx, "AsyncClient", factory)

    return install


def _saved_config(api_key: str = SAVED_SECRET) -> ProviderConfig:
    return ProviderConfig(
        role="chat",
        provider="openai_compatible",
        base_url="https://saved.example.com/v1",
        model="saved-model",
        api_key=api_key,
        capabilities=ProviderCapabilities(chat=True),
    )


def _save_config_for_user(configs: Sequence[ProviderConfig]) -> None:
    """装「该用户**自己**那份」配置（借密钥那条路径读的就是它）。"""
    runtime.use_own_user_config_source(lambda _user_id: configs)


def _list_response(items: list[object], *, status: int = 200) -> VendorHandler:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/models")
        return httpx.Response(status, json={"object": "list", "data": items})

    return handler


async def post_models(
    app: FastAPI,
    secret: str,
    payload: dict,
    *,
    nonce: str = FIXED_NONCE,
    user_id: int = 7,
) -> tuple[int, dict]:
    body = json.dumps(payload, ensure_ascii=False)
    headers = {
        **signed_headers(
            "POST",
            "/provider/models",
            secret=secret,
            body=body,
            role="AUTHOR",
            user_id=user_id,
            nonce=nonce,
        ),
        "Content-Type": "application/json",
    }
    response = await call(app, "POST", "/provider/models", headers=headers, content=body)
    return response.status_code, response.json()


def _request(**overrides: object) -> dict:
    payload: dict = {
        "provider": "openai_compatible",
        "baseUrl": PUBLIC_BASE,
        "apiKey": SECRET,
        "role": "chat",
    }
    payload.update(overrides)
    return payload


# ---------------------------------------------------------------- 门槛与形状


async def test_requires_signature(app: FastAPI, vendor: object) -> None:
    """模型清单是内部端点：它会**代用户**向任意外部地址发请求（SSRF 的落点）。"""
    response = await call(app, "POST", "/provider/models", json=_request())

    assert response.status_code == 401


async def test_returns_the_contract(app: FastAPI, secret: str, vendor: Callable) -> None:
    vendor(_list_response([{"id": "m-1", "created": 1730000000}, {"id": "m-2"}]))

    status, payload = await post_models(app, secret, _request())

    assert status == 200
    assert set(payload) == RESULT_FIELDS
    assert payload["models"] == [
        {"id": "m-1", "created": 1730000000},
        {"id": "m-2", "created": None},
    ]
    assert payload["truncated"] is False
    # source 是实际请求的 baseUrl：让用户核对自己填得对不对
    assert payload["source"] == PUBLIC_BASE
    for model in payload["models"]:
        assert set(model) == MODEL_FIELDS


# ------------------------------------------------------------------ 密钥来源


async def test_key_from_the_request_reaches_the_provider(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    """请求里给了 Key 就用它 —— 断言的是**上游真的收到了它**，不是「返回了什么」。"""
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["authorization"] = request.headers.get("authorization", "")
        return httpx.Response(200, json={"data": [{"id": "m-1"}]})

    vendor(handler)

    await post_models(app, secret, _request())

    assert seen["authorization"] == f"Bearer {SECRET}"


async def test_falls_back_to_the_saved_key_of_that_role(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    """`apiKey` 留空 → 用**该用户自己保存的该角色**密钥（与面板读同一张表、同一把主密钥）。"""
    _save_config_for_user([_saved_config()])
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["authorization"] = request.headers.get("authorization", "")
        return httpx.Response(200, json={"data": [{"id": "m-1"}]})

    vendor(handler)

    status, payload = await post_models(app, secret, _request(apiKey=None))

    assert status == 200
    assert seen["authorization"] == f"Bearer {SAVED_SECRET}"
    assert SAVED_SECRET not in json.dumps(payload, ensure_ascii=False)


def _recording(seen: list[httpx.Request]) -> VendorHandler:
    """记录出站请求，供「上游到底收到了哪把密钥」这类断言使用。"""

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json={"object": "list", "data": [{"id": "m-1"}]})

    return handler


def _authorization_of(request: httpx.Request) -> str:
    """该请求带的 `Authorization` 头（没有就是空串）。"""
    return request.headers.get("authorization", "")


async def test_never_borrows_the_global_config_key(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    """**绝不借用站长那份全局配置的密钥**（这条是安全回归，不是洁癖）。

    拉清单的 `baseUrl` 是**用户自己填的**。若借出全局那份的密钥，任何登录用户只要
    把地址填成自己的服务器、`apiKey` 留空，服务端就会把站长的明文 Key 发过去 ——
    一次请求偷走一把 Key，而日志里只留下「有人拉了一次模型清单」。

    ⚠️ 2026-10-05 起「他自己没配」不再提前 400，而是**空手去试一次**（有些供应商的
    `/models` 是公开的）。所以这里断言的不再是「不该发请求」，而是**发了请求、但头里
    没有那把全局密钥** —— 新口径下这才是安全边界的准确表达，也比旧断言更贴近真实行为。
    """
    seen: list[httpx.Request] = []
    # 全局那份（站长配的）在两条来源里都存在；用户自己那份是空的
    runtime.use_provider_configs([_saved_config()])
    runtime.use_user_config_source(lambda _user_id: [_saved_config()])
    _save_config_for_user([])
    vendor(_recording(seen))

    status, payload = await post_models(app, secret, _request(apiKey=None))

    assert status == 200, "没有可借的密钥也要照常试一次，由供应商来回答"
    assert [m["id"] for m in payload["models"]] == ["m-1"]
    assert len(seen) == 1, "应该恰好发出去一次请求"
    assert not _authorization_of(seen[0]), "绝不能把站长那把密钥发往用户自己填的地址"


async def test_other_roles_saved_key_is_not_used(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    """只取请求点名的那个角色：串到别的角色就是「用别人的配置发请求」。"""
    seen: list[httpx.Request] = []
    _save_config_for_user(
        [
            ProviderConfig(
                role="fast",
                provider="openai_compatible",
                base_url=PUBLIC_BASE,
                model="fast-model",
                api_key="sk-fast-key",
                capabilities=ProviderCapabilities(chat=True),
            )
        ]
    )
    vendor(_recording(seen))

    status, payload = await post_models(app, secret, _request(apiKey=None, role="chat"))

    assert status == 200
    assert not _authorization_of(seen[0]), "fast 那把密钥不该被拿去请求 chat 的清单"


async def test_no_key_anywhere_still_tries_once(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    """两处都没有密钥 → **不带 `Authorization` 头试一次**，而不是提前判死。

    为什么不再回 400：`/models` 是否公开由**供应商**决定，有些本来就是公开的。
    提前说「没有可用的 API Key」会让用户以为功能坏了，而实际只是「这家要密钥」——
    由对方回 401 比我们自己猜更接近事实（那句 401 的文案另有用例盯着）。
    """
    seen: list[httpx.Request] = []
    _save_config_for_user([])
    vendor(_recording(seen))

    status, payload = await post_models(app, secret, _request(apiKey=None))

    assert status == 200
    assert [m["id"] for m in payload["models"]] == ["m-1"]
    assert not _authorization_of(seen[0]), "没有密钥时不该凭空编一个 Authorization 头"


async def test_saved_config_without_a_key_also_tries_once(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    """已保存的配置存在但没密钥（协议可能是 fake）：同样空手试一次，而不是含糊地报「未配置」。"""
    seen: list[httpx.Request] = []
    _save_config_for_user([_saved_config(api_key="")])
    vendor(_recording(seen))

    status, payload = await post_models(app, secret, _request(apiKey=None))

    assert status == 200
    assert not _authorization_of(seen[0])


async def test_config_read_failure_is_a_readable_400(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    """读不到已保存配置（库连不上/密文解不开）：400 且要说清是**读配置**失败。

    混成「地址不对」会把排查方向带偏到用户填的那一栏。
    """

    def broken_source(_user_id: int) -> Sequence[ProviderConfig]:
        raise ProviderConfigError("连接 MySQL 时读不到模型配置")

    runtime.use_own_user_config_source(broken_source)
    vendor(_list_response([{"id": "m-1"}]))

    status, payload = await post_models(app, secret, _request(apiKey=None))

    assert status == 400
    assert "读取已保存的模型配置失败" in payload["message"]


async def test_unknown_role_is_a_400_not_a_502(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    """未知角色是**参数问题**：`capability_of` 抛的是装配期口径的 ProviderError（默认落 502），
    这里必须改判成 400。"""
    vendor(_list_response([{"id": "m-1"}]))

    status, payload = await post_models(app, secret, _request(role="nope"))

    assert status == 400
    assert "nope" in payload["message"]


# ------------------------------------------------------------------ 失败分档


async def test_provider_401_comes_back_as_401(app: FastAPI, secret: str, vendor: Callable) -> None:
    """供应商说密钥无效 → 401（只有人类能修：换一把 Key）。"""
    vendor(_list_response([], status=401))

    status, payload = await post_models(app, secret, _request())

    assert status == 401
    assert "密钥" in payload["message"]


async def test_provider_5xx_comes_back_as_502(app: FastAPI, secret: str, vendor: Callable) -> None:
    vendor(_list_response([], status=503))

    status, payload = await post_models(app, secret, _request())

    assert status == 502
    assert "503" in payload["message"]


async def test_provider_without_a_models_endpoint_is_502_with_a_hint(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    """「不支持 /models」也是 502：请求没错，要改的是地址（提示里必须说出来）。"""
    vendor(_list_response([], status=404))

    status, payload = await post_models(app, secret, _request())

    assert status == 502
    assert "baseUrl" in payload["message"]


async def test_unknown_provider_is_a_400(app: FastAPI, secret: str, vendor: Callable) -> None:
    vendor(_list_response([{"id": "m-1"}]))

    status, payload = await post_models(app, secret, _request(provider="anthropic"))

    assert status == 400
    assert "anthropic" in payload["message"]


async def test_private_base_url_is_rejected(app: FastAPI, secret: str, vendor: Callable) -> None:
    """地址来自浏览器表单 → 与个人配置同一档：只允许公网（防 SSRF）。"""
    vendor(_list_response([{"id": "m-1"}]))

    status, payload = await post_models(
        app, secret, _request(baseUrl="http://169.254.169.254/latest/meta-data")
    )

    assert status == 400
    assert "链路本地" in payload["message"]


async def test_malformed_json_is_502(app: FastAPI, secret: str, vendor: Callable) -> None:
    """畸形响应给可读原因（502：上游坏了），而不是一个空清单冒充成功。"""
    vendor(lambda _: httpx.Response(200, text="<html>not json</html>"))

    status, payload = await post_models(app, secret, _request())

    assert status == 502
    assert "JSON" in payload["message"]


async def test_long_list_is_truncated_and_says_so(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    vendor(
        _list_response(
            [{"id": f"m-{index}"} for index in range(model_listing.MODEL_LIST_LIMIT + 20)]
        )
    )

    status, payload = await post_models(app, secret, _request())

    assert status == 200
    assert len(payload["models"]) == model_listing.MODEL_LIST_LIMIT
    assert payload["truncated"] is True, "截断了就要如实说，否则界面会以为「就这几个」"


# ------------------------------------------------------------------ 红线


async def test_response_never_contains_the_plaintext_key(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    """**连掩码都不回**：响应体里既没有明文，也没有任何形式的密钥字段。"""
    vendor(_list_response([{"id": "m-1"}]))

    status, payload = await post_models(app, secret, _request())

    rendered = json.dumps(payload, ensure_ascii=False)
    assert status == 200
    assert SECRET not in rendered
    assert "sk-" not in rendered
    assert "apiKey" not in rendered and "api_key" not in rendered


async def test_plaintext_key_never_reaches_the_logs(
    app: FastAPI, secret: str, vendor: Callable, caplog: pytest.LogCaptureFixture
) -> None:
    """日志里也不能有它 —— 进了日志就再也收不回来（成功与失败两条路径都要经得起这一查）。"""
    vendor(_list_response([{"id": "m-1"}]))

    with caplog.at_level("INFO"):
        await post_models(app, secret, _request())
        vendor(_list_response([], status=401))
        await post_models(app, secret, _request(), nonce="aabbccddeeff00112233445566778899")

    assert any("模型清单已拉取" in record.getMessage() for record in caplog.records), (
        "日志没有捕获到任何东西，这条断言就没有意义了"
    )
    assert SECRET not in caplog.text
    assert "Bearer" not in caplog.text


async def test_fake_protocol_works_without_any_key(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    """`fake` 必须能走通：回它自己那**一个**标识，且 `source` 是 `fake` 而不是 URL。

    这条同时守着「不预置任何清单」：清单里唯一的那个名字来自 `FakeProvider.MODEL_TAG`。
    """
    _save_config_for_user([])
    vendor(lambda _: pytest.fail("fake 不该发请求"))

    status, payload = await post_models(app, secret, _request(provider="fake", apiKey=None))

    assert status == 200
    assert payload["source"] == "fake"
    assert [model["id"] for model in payload["models"]] == ["fake"]
    assert payload["truncated"] is False


async def test_fake_accepts_a_null_base_url(app: FastAPI, secret: str, vendor: Callable) -> None:
    """`baseUrl` 为 **null** 也要能走通（`fake` 不需要地址）。

    这不是吹毛求疵：Java 侧把「地址栏是空的」归一成 `null` 再转发 ——
    契约层若只收字符串，上面那条 `fake` 用例在真实链路上会变成一个 422「请求参数不合法」，
    而两侧各自的单测都还是绿的（这正是跨语言契约最容易漏的那种洞）。
    """
    _save_config_for_user([])
    vendor(lambda _: pytest.fail("fake 不该发请求"))

    status, payload = await post_models(
        app, secret, _request(provider="fake", apiKey=None, baseUrl=None)
    )

    assert status == 200
    assert payload["source"] == "fake"


async def test_null_base_url_is_a_readable_400_for_real_protocols(
    app: FastAPI, secret: str, vendor: Callable
) -> None:
    """非 fake 协议给 null/空地址 → 400 且说清「地址不能为空」（不替它编一个默认地址）。"""
    vendor(_list_response([{"id": "m-1"}]))

    status, payload = await post_models(app, secret, _request(baseUrl=None))

    assert status == 400
    assert "地址" in payload["message"]
