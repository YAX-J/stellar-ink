"""MCP（Model Context Protocol）最小协议层：JSON-RPC 2.0 的编解码与错误码。

只实现**工具**这一类能力（`tools/list` / `tools/call`），因为星笺的只读工具就是全部对外能力：
资源（resources）与提示（prompts）现在没有可暴露的东西，先把协议做扎实比铺开能力有用。

为什么手写而不是引 MCP SDK：
- 真实用到的只有三个方法（`initialize` / `tools/list` / `tools/call`），而 SDK 会引入一套
  自己的传输与生命周期抽象；本仓库的口径是「薄封装实现，框架只在真需要状态机时引入」。
- 协议里最容易出错的是**错误语义**（协议错误 vs 工具执行错误），那部分必须自己写清楚，
  而不是交给一层翻译。

参考：MCP 2025-06-18 规范。三处刻意的取舍：

1. **协议错误与工具错误的边界**（规范原话的落点）：
   - JSON 解析失败 / 方法不存在 / 参数不合法 / **工具不存在** → JSON-RPC error；
   - 工具**跑起来之后**失败（检索炸了、模型超时）→ `result.isError = true`，
     因为那是「工具执行结果」，客户端应当把这段文本喂回模型而不是当成协议故障。
2. **权限不足用实现定义的错误码 `-32003`**（-32000..-32099 是留给实现的区间）：
   它既不是「工具执行失败」（模型不该重试），也不是标准协议错误（不是消息格式问题）。
   客户端拿到它应当**停止**，而不是换个参数再试。
3. **`inputSchema` 里没有的参数一律拒绝**：这是防「自行构造参数扩大权限」的落点之一
   （作者身份走签名头，不走参数）。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

#: 本服务实现并对外承诺的协议版本。客户端版本不一致时**如实回自己的版本**，
#: 让双方各退一步去协商，而不是假装支持对方的版本。
PROTOCOL_VERSION = "2025-06-18"

SERVER_NAME = "stellar-ink-ai"
SERVER_VERSION = "1.0.0"


class RpcErrorCode:
    """JSON-RPC 标准错误码 + 本实现补充的一个。"""

    PARSE_ERROR = -32700
    INVALID_REQUEST = -32600
    METHOD_NOT_FOUND = -32601
    INVALID_PARAMS = -32602
    INTERNAL_ERROR = -32603
    #: 权限不足（实现定义区间）。客户端应当停止并上报，不要重试
    FORBIDDEN = -32003


@dataclass(frozen=True, slots=True)
class RpcRequest:
    """一条已解析的 JSON-RPC 请求。`id` 为 None 表示这是**通知**（不需要响应）。"""

    method: str
    params: dict[str, Any]
    id: Any
    is_notification: bool


class RpcError(Exception):
    """协议级错误：会被翻译成 JSON-RPC 的 `error` 对象。"""

    def __init__(self, code: int, message: str, *, data: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.data = data or {}


def parse_request(payload: Any) -> RpcRequest:
    """把解析好的 JSON（`dict`）变成 `RpcRequest`；形状不对就抛协议错误。

    ⚠️ 这里校验的是 **JSON-RPC 信封**，不是业务参数 —— 业务参数由工具自己的 schema 管，
    两件事混在一起会让「模型传错参数」和「客户端协议实现有问题」报同一个错。
    """
    if not isinstance(payload, dict):
        raise RpcError(RpcErrorCode.INVALID_REQUEST, "请求必须是 JSON 对象")
    method = payload.get("method")
    if not isinstance(method, str) or not method:
        raise RpcError(RpcErrorCode.INVALID_REQUEST, "缺少 method")
    version = payload.get("jsonrpc")
    if version != "2.0":
        raise RpcError(RpcErrorCode.INVALID_REQUEST, f"jsonrpc 必须是 2.0（收到 {version!r}）")
    params = payload.get("params")
    if params is None:
        params = {}
    if not isinstance(params, dict):
        # 规范允许数组位置参数，但本服务的工具都按名字传参；数组一律拒绝而不是猜
        raise RpcError(RpcErrorCode.INVALID_PARAMS, "params 必须是对象（本服务按名传参）")
    has_id = "id" in payload
    return RpcRequest(
        method=method,
        params=params,
        id=payload.get("id"),
        is_notification=not has_id,
    )


def parse_text(text: str) -> RpcRequest:
    """从原始请求体解析；坏 JSON 抛 `-32700`（与「信封不对」区分开）。"""
    try:
        payload = json.loads(text) if text.strip() else None
    except ValueError as error:
        raise RpcError(RpcErrorCode.PARSE_ERROR, f"请求体不是合法 JSON：{error}") from error
    if payload is None:
        raise RpcError(RpcErrorCode.PARSE_ERROR, "请求体为空")
    return parse_request(payload)


def result_response(request_id: Any, result: dict[str, Any]) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def error_response(request_id: Any, error: RpcError) -> dict[str, Any]:
    body: dict[str, Any] = {"code": error.code, "message": error.message}
    if error.data:
        body["data"] = error.data
    return {"jsonrpc": "2.0", "id": request_id, "error": body}


def server_info() -> dict[str, Any]:
    return {"name": SERVER_NAME, "version": SERVER_VERSION}
