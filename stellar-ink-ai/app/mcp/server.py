"""MCP 工具服务：把只读工具集暴露成标准协议（E3-3）。

**它只是协议层**（E3-3 的计划原文）：权限仍由 Java 的网关与服务内复核决定，工具白名单仍由
`ToolBox` 在装配时把关。这个模块新增的只有三件事：

1. **标准化的工具描述**：`tools/list` 直接由 `ToolSpec` 生成（含 JSON Schema、只读提示、幂等提示），
   客户端不必为每个模型各写一套接口；
2. **参数白名单**：schema 里没声明的参数一律拒绝。作者身份、可见范围这类东西**只从签名头来**，
   所以「客户端自行构造参数扩大权限」在协议层就落不了地；
3. **权限标签**：每条工具声明最低角色，越权调用返回实现定义错误码 `-32003` 并说明需要什么角色。

审计：每次 `tools/call` 都会带上 `_meta`（工具名、调用方角色、traceId、耗时、是否只读、是否幂等），
与调用账（E3-1）里的 `scene=agent` 记录互相印证。
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from app.core.internal_auth import InternalIdentity
from app.mcp.protocol import (
    PROTOCOL_VERSION,
    RpcError,
    RpcErrorCode,
    RpcRequest,
    error_response,
    result_response,
    server_info,
)
from app.rag.agent import ToolBox, ToolSpec
from app.schemas.common import Role

logger = logging.getLogger(__name__)

#: `tools/call` 的参数名（MCP 规范固定）
_ARG_NAME = "name"
_ARG_ARGUMENTS = "arguments"


class McpToolServer:
    """一个绑定到固定工具集的 MCP 服务端（无状态，可并发调用）。"""

    def __init__(self, tools: ToolBox) -> None:
        self._tools = tools

    @property
    def tool_names(self) -> list[str]:
        return self._tools.names

    async def handle(
        self, request: RpcRequest, *, identity: InternalIdentity
    ) -> dict[str, Any] | None:
        """处理一条请求；返回 `None` 表示这是一条通知（按规范不回响应）。"""
        if request.is_notification:
            # 通知（如 notifications/initialized）没有 id：**不能**回响应，
            # 回了就是协议违规，客户端会把它当成一条凭空出现的响应
            logger.debug("MCP 通知：%s", request.method)
            return None

        if request.method == "initialize":
            return result_response(request.id, self._initialize_result())
        if request.method == "ping":
            return result_response(request.id, {})
        if request.method == "tools/list":
            return result_response(request.id, {"tools": self._tool_entries()})
        if request.method == "tools/call":
            return await self._call_tool(request, identity=identity)
        raise RpcError(
            RpcErrorCode.METHOD_NOT_FOUND,
            f"不支持的方法：{request.method}",
            data={"supported": ["initialize", "ping", "tools/list", "tools/call"]},
        )

    def handle_error(self, request_id: Any, error: RpcError) -> dict[str, Any]:
        return error_response(request_id, error)

    # ------------------------------------------------------------------ 方法实现

    def _initialize_result(self) -> dict[str, Any]:
        """握手结果。`instructions` 里写明两条边界，客户端一读就知道该怎么用。"""
        return {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": server_info(),
            "instructions": (
                "星笺的只读文章工具。全部工具只读且幂等；调用方身份（含作者身份与可见范围）"
                "由服务端从签名的身份头确定，参数里传任何身份字段都会被拒绝。"
            ),
        }

    def _tool_entries(self) -> list[dict[str, Any]]:
        return [
            {
                "name": spec.name,
                "description": spec.description,
                "inputSchema": spec.input_schema
                or {"type": "object", "properties": {}, "additionalProperties": False},
                # MCP 的 annotations 是**提示**（客户端可用来决定要不要自动执行），
                # 真正的约束在服务端：只读由 ToolBox 保证，权限由下面的 requiredRole 判
                "annotations": {
                    "title": spec.name,
                    "readOnlyHint": spec.read_only,
                    "idempotentHint": spec.idempotent,
                    "requiredRole": str(spec.required_role),
                    "timeoutMs": spec.timeout_ms,
                },
            }
            for spec in (self._tools.get(name) for name in self._tools.names)
            if spec is not None
        ]

    async def _call_tool(
        self, request: RpcRequest, *, identity: InternalIdentity
    ) -> dict[str, Any]:
        name = request.params.get(_ARG_NAME)
        if not isinstance(name, str) or not name:
            raise RpcError(RpcErrorCode.INVALID_PARAMS, "tools/call 需要 name")
        spec = self._tools.get(name)
        if spec is None:
            # 未知工具是**协议层**问题（客户端拼错了名字），不是「工具执行失败」
            raise RpcError(
                RpcErrorCode.INVALID_PARAMS,
                f"未知工具：{name}",
                data={"available": self._tools.names},
            )

        arguments = request.params.get(_ARG_ARGUMENTS)
        if arguments is None:
            arguments = {}
        if not isinstance(arguments, dict):
            raise RpcError(RpcErrorCode.INVALID_PARAMS, "arguments 必须是对象")
        self._assert_arguments_allowed(spec, arguments)
        self._assert_role(spec, identity)

        started = time.perf_counter()
        try:
            async with asyncio.timeout(spec.timeout_ms / 1000):
                result = await spec.handler(dict(arguments))
        except TimeoutError:
            # 工具执行失败：走 isError，让客户端把它当成结果而不是协议故障
            return self._tool_result(
                request.id,
                spec,
                identity,
                text=f"工具 {spec.name} 超时（{spec.timeout_ms}ms）。",
                is_error=True,
                started=started,
            )
        except Exception as error:  # noqa: BLE001 - 工具异常一律转成 isError，别让协议层炸掉
            logger.warning("MCP 工具执行失败：tool=%s error=%s", spec.name, error)
            return self._tool_result(
                request.id,
                spec,
                identity,
                text=f"工具 {spec.name} 执行失败：{type(error).__name__}。",
                is_error=True,
                started=started,
            )

        body = result.summary
        if result.label:
            body = f"{result.label}｜{body}"
        logger.info(
            "MCP 工具调用完成：tool=%s role=%s userId=%s latencyMs=%d citations=%d",
            spec.name,
            identity.role,
            identity.user_id,
            int((time.perf_counter() - started) * 1000),
            len(result.citations),
        )
        return self._tool_result(
            request.id,
            spec,
            identity,
            text=body,
            is_error=False,
            started=started,
            citations=[citation.model_dump(by_alias=True) for citation in result.citations],
        )

    # ------------------------------------------------------------------ 校验

    def _assert_arguments_allowed(self, spec: ToolSpec, arguments: dict[str, Any]) -> None:
        """schema 之外的一律拒绝。

        这不是「严格模式」而是**安全边界**：工具的可见范围由服务端身份决定，
        若允许透传未知参数，客户端就能试着塞 `authorId` / `userId` / `role` 去改变行为
        （工具实现忽略它们只是运气好，而不是约定）。
        """
        allowed = spec.argument_names()
        unexpected = sorted(set(arguments) - allowed)
        if unexpected:
            raise RpcError(
                RpcErrorCode.INVALID_PARAMS,
                f"工具 {spec.name} 不接受这些参数：{'、'.join(unexpected)}",
                data={"allowed": sorted(allowed)},
            )

    def _assert_role(self, spec: ToolSpec, identity: InternalIdentity) -> None:
        required = spec.required_role
        actual = _role_of(identity.role)
        if actual is None or not actual.at_least(required):
            raise RpcError(
                RpcErrorCode.FORBIDDEN,
                f"工具 {spec.name} 需要 {required} 权限。",
                data={"requiredRole": str(required), "tool": spec.name},
            )

    # ------------------------------------------------------------------ 结果整形

    def _tool_result(
        self,
        request_id: Any,
        spec: ToolSpec,
        identity: InternalIdentity,
        *,
        text: str,
        is_error: bool,
        started: float,
        citations: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        """MCP 的工具结果：正文放 `content`，结构化线索放 `structuredContent`。

        `citations` 刻意用 `structuredContent` 而不是塞进正文：引用要能被程序消费
        （前端据此跳原文），而正文是给模型读的自然语言 —— 两者的读者不同。
        """
        result: dict[str, Any] = {
            "content": [{"type": "text", "text": text}],
            "isError": is_error,
            "_meta": {
                "tool": spec.name,
                "role": identity.role,
                "traceId": identity.trace_id,
                "latencyMs": int((time.perf_counter() - started) * 1000),
                "readOnly": spec.read_only,
                "idempotent": spec.idempotent,
            },
        }
        if citations:
            result["structuredContent"] = {"citations": citations}
        return result_response(request_id, result)


def _role_of(raw: str | None) -> Role | None:
    """把签名头里的角色字符串变成枚举；未知角色返回 None（**不放行**）。"""
    if not raw:
        return None
    try:
        return Role(str(raw).strip().upper())
    except ValueError:
        return None
