"""MCP（Model Context Protocol）工具服务（E3-3）。

对外只有 `app/mcp/server.py` 的 `McpToolServer` 与 `app/mcp/protocol.py` 的编解码；
HTTP 出口在 `app/api/v1/mcp.py`（`POST /mcp`，受内部签名保护）。
"""
