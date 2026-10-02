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


#: 当前允许暴露的路径（不含文档）。
#: 这个集合是**有意的白名单**：新增路由会让本测试失败，从而逼着人回来确认
#: 「它确实该暴露、且已经写进 docs/api/README.md」，而不是顺手加一个接口。
EXPOSED_PATHS = {
    "/health",
    # 评测台（C 阶段）：受内部签名保护，对外由 ai-service 的 ADMIN 门槛转发
    "/eval/datasets",
    "/eval/strategies",
    "/eval/run",
    # 星海问答（D 阶段）：`/qa` 非流式，`/qa/stream` 是 SSE（协议见 schemas/qa_stream.py）
    "/qa",
    "/qa/stream",
    # 写作建议（D 阶段）：只返回候选，不写正文
    "/writing/suggest",
    # 写作风格画像（E1）：只量的统计量，仍是写操作协议（POST 带请求体）
    "/writing/style",
    # 只读 Agent（E2）：受内部签名保护，工具全部只读
    "/agent/ask",
    # MCP 工具服务（E3-3）：JSON-RPC 2.0，工具集与 Agent 同一份；同样受内部签名保护
    "/mcp",
    # 按 traceId 回放（E3-4）：运维排障用，只在内网可达
    "/internal/trace/{trace_id}",
    # LLM Wiki 主张抽取（E4-1）：内部签名保护，落库与读者侧页面在 Java 侧
    "/wiki/claims",
    # LLM Wiki 失效盘点（E4-11）：把库里的锚点交回来比对当前语料；只读、不花钱
    "/wiki/stale",
    # 作者记忆（M9）：Python 只出判断（候选/计划/召回集合），落库与状态流转在 Java
    "/memory/candidates",
    "/memory/plan",
    "/memory/recall",
}


def test_exposed_paths_are_the_intended_whitelist(app: FastAPI) -> None:
    """公开路由白名单：探活 + 评测接口（非生产另有内部签名自检）。

    问答 / 写作建议 / 索引 / MCP 接口分别属于 D、B、E3 阶段，出现即说明越界开发
    （docs/ai/development-workflow.md §7.6：新接口先写进 docs/api 再实现）。
    用 OpenAPI schema 判断而不是遍历内部路由对象，避免绑定框架内部结构。
    """
    exposed = {path for path in app.openapi()["paths"] if not path.startswith(("/docs", "/redoc"))}

    # 测试环境（非 prod）额外暴露内部签名自检，用于验证 A2 的签名链路
    assert exposed == EXPOSED_PATHS | {"/internal/whoami"}


def test_eval_paths_are_not_public(app: FastAPI) -> None:
    """评测接口必须走内部签名：它消耗算力、（将来）还花模型的钱。"""
    from app.core.internal_auth_middleware import is_public_path

    for path in EXPOSED_PATHS - {"/health"}:
        assert not is_public_path(path), f"{path} 不该出现在公开白名单里"
