"""生成「拉取供应商模型清单」的契约 fixture（Java 与 Python 两侧读同一份）。

与 `gen_agent_fixture.py` 同样的纪律：**离线、确定、不依赖任何真实供应商**。
这里用一个 `httpx.MockTransport` 假装供应商回了 `GET /models`，然后走**真实代码路径**
（`list_provider_models`）产出结果 —— 手工写的期望值只能证明「我以为它长这样」，
而它恰恰是两侧契约测试唯一的共同依据。

样例刻意覆盖两种条目形态：
- 带 `created` 的（有服务返回时间戳）；
- **不带 `created` 的**（多数服务不返回）—— 它是 `created` 可空的那条**唯一**凭据，
  手工改 fixture 把它补上，就等于把「可空」这件事从契约里删掉了。

用法::

    uv run python scripts/gen_provider_models_fixture.py
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import httpx

from app.providers.model_listing import ProviderModelList, list_provider_models
from app.schemas import (
    ProviderModelEntry,
    ProviderModelsRequest,
    ProviderModelsResult,
)

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console

FIXTURES = Path(__file__).resolve().parents[1] / "tests" / "fixtures"
REQUEST_FIXTURE = FIXTURES / "provider_models_request.json"
RESULT_FIXTURE = FIXTURES / "provider_models_result.json"

#: fixture 里的地址：**必须是不存在的示例域名**，不能是真端点（样例会被所有人读）
BASE_URL = "https://api.example.com/v1"

#: 请求样例：apiKey 是**占位符**，不是任何人的密钥（契约只约定「键名是 apiKey、明文只出现这一次」）
API_KEY_PLACEHOLDER = "sk-fixture-not-a-real-key"

#: 假供应商的响应：三条，最后一条**没有 created**（可空字段的凭据）
VENDOR_RESPONSE = {
    "object": "list",
    "data": [
        {"id": "example-chat", "object": "model", "created": 1730000000, "owned_by": "example"},
        {"id": "example-reasoner", "object": "model", "created": 1730003600, "owned_by": "example"},
        {"id": "example-embed", "object": "model", "owned_by": "example"},
    ],
}


def build_result() -> dict:
    """走真实代码路径产出结果（假 transport，不打网络、不需要密钥）。"""

    def handler(request: httpx.Request) -> httpx.Response:
        # 顺带把「路径是代码拼出来的 /models」钉住：改了拼接方式，生成时立刻失败。
        # 用显式 raise 而不是 assert：脚本不在 tests/ 下，assert 会被 ruff 的 S101 判为问题
        if not request.url.path.endswith("/models"):
            raise RuntimeError(f"拼接出来的路径不对：{request.url.path}")
        return httpx.Response(200, json=VENDOR_RESPONSE)

    listing = asyncio.run(
        list_provider_models(
            provider="openai_compatible",
            base_url=BASE_URL,
            api_key=API_KEY_PLACEHOLDER,
            transport=httpx.MockTransport(handler),
        )
    )
    return listing_to_payload(listing)


def listing_to_payload(listing: ProviderModelList) -> dict:
    """`ProviderModelList` → 契约形状（**经 schema 转一次**，保证键名与结果契约逐字一致）。

    直接用 `model_dump(by_alias=True)`：手写字典迟早与 `app/schemas/provider.py` 分叉，
    而这份文件正是两侧契约测试唯一的共同依据。
    """
    result = ProviderModelsResult(
        models=[ProviderModelEntry(id=model.id, created=model.created) for model in listing.models],
        truncated=listing.truncated,
        source=listing.source,
    )
    return result.model_dump(by_alias=True, mode="json")


def build_request() -> dict:
    request = ProviderModelsRequest(
        provider="openai_compatible",
        base_url=BASE_URL,
        api_key=API_KEY_PLACEHOLDER,
        role="chat",
    )
    return request.model_dump(by_alias=True, mode="json")


def main() -> None:
    REQUEST_FIXTURE.write_text(
        json.dumps(build_request(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    RESULT_FIXTURE.write_text(
        json.dumps(build_result(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"已写入 {REQUEST_FIXTURE.name} 与 {RESULT_FIXTURE.name}")


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/破折号会让 print 抛异常
    use_utf8_console()
    main()
