"""MCP 工具服务（E3-3）：协议语义、权限标签与「参数不能用来扩权」。

这一层最该被钉住的不是「能不能用」，而是**边界**：
越权调用返回什么、schema 之外的参数怎么办、工具自己炸了算协议错误还是执行错误。
这些判据一旦放松，宿主（模型）就会把「没权限」当成「换个参数再试」，
最后表现成「MCP 客户端能查到它不该查的东西」——而那是本轮验收里唯一的安全条目。
"""

from __future__ import annotations

import asyncio
import json
from typing import Any

import pytest

from app.core.internal_auth import InternalIdentity
from app.mcp.protocol import (
    PROTOCOL_VERSION,
    RpcError,
    RpcErrorCode,
    parse_request,
    parse_text,
)
from app.mcp.server import McpToolServer
from app.rag.agent import ToolBox, ToolResult, ToolSpec
from app.rag.agent_tools import read_only_tools
from app.schemas.common import Citation, Role


async def _echo(arguments: dict[str, Any]) -> ToolResult:
    return ToolResult(summary=f"收到：{arguments.get('question', '')}", label="回显")


def _tool(name: str = "search_posts", **overrides: Any) -> ToolSpec:
    base: dict[str, Any] = {
        "name": name,
        "description": "测试工具",
        "handler": _echo,
        "input_schema": {
            "type": "object",
            "properties": {"question": {"type": "string"}},
            "required": ["question"],
            "additionalProperties": False,
        },
        "required_role": Role.READER,
    }
    base.update(overrides)
    return ToolSpec(**base)


def _server(*tools: ToolSpec) -> McpToolServer:
    return McpToolServer(ToolBox(list(tools) or [_tool()]))


def _identity(role: str = "READER") -> InternalIdentity:
    return InternalIdentity(user_id=7, role=role, trace_id="trace-1")


def _request(method: str, params: dict[str, Any] | None = None, *, request_id: Any = 1):
    payload: dict[str, Any] = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        payload["params"] = params
    if request_id is not None:
        payload["id"] = request_id
    return parse_request(payload)


# --------------------------------------------------------------------- 协议信封


def test_parse_rejects_non_object_body() -> None:
    with pytest.raises(RpcError) as info:
        parse_request(["not", "an", "object"])

    assert info.value.code == RpcErrorCode.INVALID_REQUEST


def test_parse_rejects_wrong_jsonrpc_version() -> None:
    with pytest.raises(RpcError) as info:
        parse_request({"jsonrpc": "1.0", "method": "tools/list", "id": 1})

    assert info.value.code == RpcErrorCode.INVALID_REQUEST
    assert "2.0" in info.value.message


def test_parse_reports_broken_json_as_parse_error() -> None:
    """坏 JSON 是 -32700（解析失败），不是「信封不对」——两者的排查方向完全不同。"""
    with pytest.raises(RpcError) as info:
        parse_text("{ 这不是 JSON")

    assert info.value.code == RpcErrorCode.PARSE_ERROR


def test_notification_has_no_id() -> None:
    request = parse_request({"jsonrpc": "2.0", "method": "notifications/initialized"})

    assert request.is_notification is True


def test_array_params_are_rejected() -> None:
    """规范允许位置参数，但本服务按名传参：一律拒绝而不是猜哪个位置是什么。"""
    with pytest.raises(RpcError) as info:
        parse_request({"jsonrpc": "2.0", "method": "tools/call", "params": ["x"], "id": 1})

    assert info.value.code == RpcErrorCode.INVALID_PARAMS


# --------------------------------------------------------------------- 方法


async def test_initialize_returns_capabilities_and_instructions() -> None:
    response = await _server().handle(_request("initialize"), identity=_identity())

    assert response is not None
    result = response["result"]
    assert result["protocolVersion"] == PROTOCOL_VERSION
    assert result["capabilities"]["tools"]["listChanged"] is False
    assert result["serverInfo"]["name"] == "stellar-ink-ai"
    # 客户端一读就知道「身份不能从参数传」
    assert "身份" in result["instructions"]


async def test_ping_is_supported() -> None:
    response = await _server().handle(_request("ping"), identity=_identity())

    assert response == {"jsonrpc": "2.0", "id": 1, "result": {}}


async def test_unknown_method_is_a_protocol_error() -> None:
    with pytest.raises(RpcError) as info:
        await _server().handle(_request("resources/list"), identity=_identity())

    assert info.value.code == RpcErrorCode.METHOD_NOT_FOUND
    assert "tools/call" in info.value.data["supported"]


async def test_notification_gets_no_response() -> None:
    """通知不能回响应：回了就是协议违规（客户端会看到一条凭空出现的响应）。"""
    response = await _server().handle(
        _request("notifications/initialized", request_id=None), identity=_identity()
    )

    assert response is None


async def test_tools_list_exposes_schema_and_governance_fields() -> None:
    response = await _server(_tool()).handle(_request("tools/list"), identity=_identity())

    assert response is not None
    entries = response["result"]["tools"]
    assert len(entries) == 1
    entry = entries[0]
    assert entry["name"] == "search_posts"
    assert entry["inputSchema"]["properties"]["question"]["type"] == "string"
    annotations = entry["annotations"]
    assert annotations["readOnlyHint"] is True
    assert annotations["idempotentHint"] is True
    assert annotations["requiredRole"] == "READER"
    assert annotations["timeoutMs"] > 0


# --------------------------------------------------------------------- 调用


async def test_tool_call_returns_text_and_audit_meta() -> None:
    response = await _server(_tool()).handle(
        _request("tools/call", {"name": "search_posts", "arguments": {"question": "星笺"}}),
        identity=_identity("AUTHOR"),
    )

    assert response is not None
    result = response["result"]
    assert result["isError"] is False
    body = result["content"][0]["text"]
    assert "回显" in body and "星笺" in body
    meta = result["_meta"]
    assert meta["tool"] == "search_posts"
    assert meta["role"] == "AUTHOR"
    assert meta["traceId"] == "trace-1", "审计要能顺着 traceId 回到调用账里的那条记录"
    assert meta["readOnly"] is True and meta["idempotent"] is True


async def test_citations_go_to_structured_content_not_into_the_text() -> None:
    """引用要能被程序消费（前端跳原文），正文是给模型读的：两者的读者不同。"""

    async def with_citation(arguments: dict[str, Any]) -> ToolResult:
        del arguments
        return ToolResult(
            summary="查到一段",
            citations=[Citation(post_id=3, title="标题", chunk_index=0, snippet="片段", score=1.0)],
        )

    server = _server(_tool(handler=with_citation))
    response = await server.handle(
        _request("tools/call", {"name": "search_posts", "arguments": {"question": "q"}}),
        identity=_identity(),
    )

    assert response is not None
    structured = response["result"]["structuredContent"]["citations"]
    assert structured[0]["postId"] == 3, "键名必须与对外契约一致（驼峰）"
    assert "postId" not in response["result"]["content"][0]["text"]


async def test_unknown_tool_is_invalid_params() -> None:
    with pytest.raises(RpcError) as info:
        await _server().handle(
            _request("tools/call", {"name": "delete_everything"}), identity=_identity()
        )

    assert info.value.code == RpcErrorCode.INVALID_PARAMS
    assert info.value.data["available"] == ["search_posts"]


async def test_missing_name_is_invalid_params() -> None:
    with pytest.raises(RpcError) as info:
        await _server().handle(_request("tools/call", {}), identity=_identity())

    assert info.value.code == RpcErrorCode.INVALID_PARAMS


async def test_arguments_outside_the_schema_are_rejected() -> None:
    """**这是「客户端不能靠构造参数扩权」的落点**：作者身份只从签名头来。

    工具实现忽略未知参数只是运气好，不能当成约定 —— 真要有人哪天读了 `authorId`，
    越权就在不知不觉中成立了。
    """
    with pytest.raises(RpcError) as info:
        await _server().handle(
            _request(
                "tools/call",
                {"name": "search_posts", "arguments": {"question": "q", "authorId": 1}},
            ),
            identity=_identity(),
        )

    assert info.value.code == RpcErrorCode.INVALID_PARAMS
    assert "authorId" in info.value.message
    assert info.value.data["allowed"] == ["question"]


async def test_author_only_tool_rejects_reader() -> None:
    server = _server(_tool("author_style", required_role=Role.AUTHOR))

    with pytest.raises(RpcError) as info:
        await server.handle(
            _request("tools/call", {"name": "author_style", "arguments": {}}),
            identity=_identity("READER"),
        )

    assert info.value.code == RpcErrorCode.FORBIDDEN
    assert info.value.data == {"requiredRole": "AUTHOR", "tool": "author_style"}


async def test_unknown_role_is_denied_not_treated_as_reader() -> None:
    """拿不到合法角色时**拒绝**，而不是降级成 READER —— 降级等于把「身份坏了」变成「能用一点」。"""
    server = _server(_tool("author_style", required_role=Role.AUTHOR))

    with pytest.raises(RpcError) as info:
        await server.handle(
            _request("tools/call", {"name": "author_style", "arguments": {}}),
            identity=_identity("SUPERUSER"),
        )

    assert info.value.code == RpcErrorCode.FORBIDDEN


async def test_admin_can_call_author_tool() -> None:
    server = _server(_tool("author_style", required_role=Role.AUTHOR))

    response = await server.handle(
        _request("tools/call", {"name": "author_style", "arguments": {}}),
        identity=_identity("ADMIN"),
    )

    assert response is not None
    assert response["result"]["isError"] is False


async def test_tool_failure_is_iserror_not_protocol_error() -> None:
    """工具跑起来之后失败算**执行结果**：客户端要把它喂回模型，而不是当成协议故障。"""

    async def boom(arguments: dict[str, Any]) -> ToolResult:
        del arguments
        raise RuntimeError("检索炸了")

    response = await _server(_tool(handler=boom)).handle(
        _request("tools/call", {"name": "search_posts", "arguments": {"question": "q"}}),
        identity=_identity(),
    )

    assert response is not None
    result = response["result"]
    assert result["isError"] is True
    assert "RuntimeError" in result["content"][0]["text"], "要有可读原因，而不是一句「失败」"


async def test_tool_timeout_is_reported_as_error() -> None:
    async def slow(arguments: dict[str, Any]) -> ToolResult:
        del arguments
        await asyncio.sleep(0.2)
        return ToolResult(summary="太晚了")

    server = _server(_tool(handler=slow, timeout_ms=30))
    response = await server.handle(
        _request("tools/call", {"name": "search_posts", "arguments": {"question": "q"}}),
        identity=_identity(),
    )

    assert response is not None
    assert response["result"]["isError"] is True
    assert "超时" in response["result"]["content"][0]["text"]


# --------------------------------------------------------------------- 与真实工具集对齐


def test_real_tool_set_matches_the_mcp_contract() -> None:
    """真实工具集必须能直接生成合法 schema —— 否则 MCP 上线时才发现缺字段。"""
    pipeline_tools = read_only_tools(pipeline=_FakePipeline())  # type: ignore[arg-type]

    assert [tool.name for tool in pipeline_tools] == ["search_posts"]
    spec = pipeline_tools[0]
    assert spec.required_role is Role.READER
    assert spec.argument_names() == {"question", "topK"}
    assert spec.input_schema["additionalProperties"] is False
    # schema 能被序列化（JSON-RPC 要发出去）
    json.dumps(_server(spec)._tool_entries())  # noqa: SLF001 - 断言的就是这份对外结构

    style_tools = read_only_tools(style_posts=[object()])
    assert style_tools[0].name == "author_style"
    assert style_tools[0].required_role is Role.AUTHOR
    assert style_tools[0].argument_names() == set(), "作者身份不能从参数传"


class _FakePipeline:
    """只为装配 `SearchPostsTool` 而存在的空管线：本测试不真的检索。"""

    corpus: list[Any] = []
