"""应用装配的最小冒烟测试：探活可访问、traceId 可贯穿。

这里**不**断言任何模型/向量库行为 —— M0 没有这些依赖，出现即说明越界。
"""

import httpx
import pytest
from fastapi import FastAPI

from app import __version__
from app.main import create_app


@pytest.fixture()
def app() -> FastAPI:
    return create_app()


@pytest.fixture()
async def client(app: FastAPI) -> httpx.AsyncClient:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://ai.internal") as http_client:
        yield http_client


async def test_health_ok(client: httpx.AsyncClient) -> None:
    response = await client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "stellar-ink-ai"
    assert body["version"] == __version__
    # 健康检查不得泄露配置细节（端点、密钥、模型名都属配置）
    assert set(body) == {"service", "version", "status", "env"}


async def test_health_echoes_incoming_trace_id(client: httpx.AsyncClient) -> None:
    response = await client.get("/health", headers={"X-Trace-Id": "abc123def456"})

    assert response.headers["X-Trace-Id"] == "abc123def456"


async def test_health_generates_trace_id_when_absent(client: httpx.AsyncClient) -> None:
    response = await client.get("/health")

    trace_id = response.headers["X-Trace-Id"]
    assert len(trace_id) == 32
    assert trace_id.isalnum()


async def test_health_rejects_untrusted_trace_id(client: httpx.AsyncClient) -> None:
    # 非法（含非字母数字）的传入值必须被丢弃并换成本服务生成的 id
    response = await client.get("/health", headers={"X-Trace-Id": "bad id<script>"})

    trace_id = response.headers["X-Trace-Id"]
    assert trace_id != "bad id<script>"
    assert len(trace_id) == 32


async def test_unknown_path_is_401_without_signature(client: httpx.AsyncClient) -> None:
    """未注册路径默认拒绝：A2 起所有非公开路径都先要过内部验签。

    注意断言的是 401 而不是 404 —— 这是有意的：如果未注册路径先返回 404，
    那么「新增路由忘记加保护」会以 404/405 的形态暴露而不是被拦在门外。
    带合法签名的未知路径才应该是 404（见 test_internal_auth_wiring）。
    """
    response = await client.get("/not-a-real-path")

    assert response.status_code == 401


def test_m0_exposes_only_health(app: FastAPI) -> None:
    """公开路由白名单：只有探活（以及文档）。

    问答 / 写作建议 / 索引接口分别属于 M5 与 M3，出现即说明越界开发
    （docs/ai/development-workflow.md §7.6：新接口先写进 docs/api 再实现）。
    用 OpenAPI schema 判断而不是遍历内部路由对象，避免绑定框架内部结构。
    """
    exposed = {path for path in app.openapi()["paths"] if not path.startswith(("/docs", "/redoc"))}

    # 测试环境（非 prod）额外暴露内部签名自检，用于验证 A2 的签名链路
    assert exposed == {"/health", "/internal/whoami"}
