"""MCP 的 HTTP 出口：`POST /mcp`（仅内网可达）。

为什么走 HTTP 而不是 stdio：本服务的调用方是 Java `ai-service` 与内网里的编排进程，
它们已经有 HTTP + 内部签名的通道；stdio 传输只适合「本机拉起一个子进程」的客户端，
而那类客户端拿不到签署过的身份头 —— 没有身份就没有权限判定，等于把只读工具变成匿名可查。

⚠️ **这一层不做鉴权**：验签由 `InternalAuthMiddleware` 在路由之前完成（非公开路径一律要签名），
身份从 `request.state` 取。所以 `/mcp` 天然继承了「Python 不解析 Sa-Token、身份只由 Java 传」
的口径。对外暴露（给站外的 MCP 客户端用）需要 Java 侧再开一条带鉴权的出口，那是另一刀。
"""

from __future__ import annotations

import json
import logging
from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse

from app.api.v1.agent import AGENT_RETRIEVAL
from app.api.v1.assembly import ASSEMBLY_ERRORS, assembly_error, pipeline_for
from app.core.internal_auth import InternalIdentity
from app.core.internal_auth_middleware import require_internal_identity
from app.mcp.protocol import RpcError, RpcErrorCode, error_response, parse_text
from app.mcp.server import McpToolServer
from app.rag.agent import ToolBox
from app.rag.agent_tools import read_only_tools

logger = logging.getLogger(__name__)

router = APIRouter(tags=["mcp"])


def build_tool_server() -> McpToolServer:
    """装配 MCP 工具服务。

    工具集与 Agent 用的是**同一份**（`read_only_tools` + `AGENT_RETRIEVAL`）：
    协议层换一套工具，就会出现「Agent 能查的 MCP 查不到」这种两边慢慢分叉的问题。
    """
    tools = read_only_tools(pipeline=pipeline_for(AGENT_RETRIEVAL))
    return McpToolServer(ToolBox(tools))


@router.post("/mcp", summary="MCP 工具服务（JSON-RPC 2.0，仅内网）", response_model=None)
async def mcp(
    request: Request,
    identity: InternalIdentity = Depends(require_internal_identity),  # noqa: B008 - 见 app/main.py
) -> JSONResponse:
    """处理一条 JSON-RPC 请求。

    三处刻意的处理：

    - **解析失败也回 200 + JSON-RPC error**：MCP 的错误语义在 body 里（`-32700`），
      用 HTTP 4xx 表达会让客户端的协议层把「格式错误」误当成「服务不可用」；
    - **通知不回响应**（规范要求），因此可能是 202 空体；
    - **装配失败用 `-32003` 之外的 `-32000` 区间**：那是「服务端当前起不来」，
      与「你没权限」是两回事 —— 客户端对前者应当重试/上报，对后者应当停止。
    """
    raw = (await request.body()).decode("utf-8", errors="replace")
    try:
        parsed = parse_text(raw)
    except RpcError as error:
        logger.warning("MCP 请求解析失败：%s", error.message)
        return JSONResponse(error_response(None, error))

    try:
        server = build_tool_server()
    except ASSEMBLY_ERRORS as error:
        payload = assembly_error(error)
        # 复用装配错误的可读文案，但包成 JSON-RPC 形状：客户端只认一种错误体
        return JSONResponse(
            error_response(
                parsed.id,
                RpcError(
                    RpcErrorCode.INTERNAL_ERROR,
                    _message_of(payload),
                    data={"kind": "assembly"},
                ),
            )
        )

    try:
        response = await server.handle(parsed, identity=identity)
    except RpcError as error:
        return JSONResponse(error_response(parsed.id, error))
    if response is None:
        # 通知：没有 id，不能回 JSON-RPC 响应
        return JSONResponse(status_code=202, content=None)
    return JSONResponse(response)


def _message_of(payload: JSONResponse) -> str:
    """从装配错误的 JSON 响应里取出那句给人看的话（`{"code","message"}`）。"""
    try:
        body: dict[str, Any] = json.loads(bytes(payload.body).decode("utf-8"))
    except (ValueError, AttributeError):  # pragma: no cover - 形状由 assembly_error 保证
        return "AI 编排服务当前不可用。"
    return str(body.get("message") or "AI 编排服务当前不可用。")
