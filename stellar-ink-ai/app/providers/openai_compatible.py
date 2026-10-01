"""OpenAI 兼容实现：一套代码覆盖 DeepSeek / 硅基流动 / vLLM / SGLang 等。

为什么只做「OpenAI 兼容」这一种：国内云厂商与自建推理服务（vLLM/SGLang）几乎都提供
`/chat/completions` 与 `/embeddings` 兼容端点，重排则普遍是 `/rerank`（硅基流动风格）。
把它做扎实，换厂商就等于改 base_url + model 名，业务代码一行不动；
真出现不兼容的厂商时再新增一个 Provider 实现，而不是让业务层到处 if。

约定：
- 所有请求都带 `Authorization: Bearer <key>`（密钥只在这里出现，不写日志）。
- 失败映射成 `app.providers.errors` 的分类错误，**不把上游原始报文透给用户**。
- 具备哪种能力由调用方通过 `ProviderCapabilities` 声明，缺能力时立刻报错，
  不做「先试一下再看」的探测（那会白花一次调用与 Token）。
"""

from __future__ import annotations

import json
import logging
import time
from collections.abc import AsyncIterator
from typing import Any

import httpx

from app.core.trace import record_event
from app.providers.errors import (
    ProviderAuthError,
    ProviderError,
    ProviderRateLimitError,
    ProviderTimeoutError,
    ProviderUnavailableError,
    UnsupportedCapabilityError,
)
from app.providers.models import (
    ChatMessage,
    ChatResponse,
    ChatStreamChunk,
    EmbeddingResponse,
    ProviderCapabilities,
    ProviderConfig,
    RerankResponse,
    RerankResult,
    TokenUsage,
)

logger = logging.getLogger(__name__)


class OpenAICompatibleProvider:
    """一个实例只服务一个角色（如 chat 或 embedding），能力在构造时声明。"""

    def __init__(
        self,
        config: ProviderConfig,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._config = config
        self._caps = config.capabilities
        self._client = httpx.AsyncClient(
            base_url=config.base_url.rstrip("/"),
            timeout=httpx.Timeout(config.timeout_ms / 1000),
            headers={"Authorization": f"Bearer {config.api_key}"},
            transport=transport,
        )

    @property
    def config(self) -> ProviderConfig:
        return self._config

    @property
    def capabilities(self) -> ProviderCapabilities:
        return self._caps

    async def aclose(self) -> None:
        await self._client.aclose()

    # ---------------------------------------------------------------- chat

    async def chat(
        self,
        messages: list[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> ChatResponse:
        self._require("chat")
        payload: dict[str, Any] = {
            "model": self._config.model,
            "messages": [message.to_payload() for message in messages],
            "stream": False,
        }
        resolved_temperature = self._resolve(temperature, self._config.temperature)
        if resolved_temperature is not None:
            payload["temperature"] = resolved_temperature
        resolved_max_tokens = max_tokens or self._config.max_tokens
        if resolved_max_tokens is not None:
            payload["max_tokens"] = resolved_max_tokens

        started = time.perf_counter()
        data = await self._post("/chat/completions", payload)
        latency_ms = _elapsed_ms(started)

        choice = _first(data.get("choices"))
        message = choice.get("message") if isinstance(choice, dict) else None
        text = str(message.get("content") or "") if isinstance(message, dict) else ""
        usage = _as_mapping(data.get("usage"))
        finish_reason = choice.get("finish_reason") if isinstance(choice, dict) else None
        record = TokenUsage.of(
            _as_int(usage.get("prompt_tokens")),
            _as_int(usage.get("completion_tokens")),
            latency_ms=latency_ms,
            model=self._config.model,
        )
        # 链路事件只记结构：哪个角色、哪个模型、多少 token、多久、怎么结束的（**不记内容**）
        record_event(
            "model",
            call="chat",
            role=self._config.role,
            model=self._config.model,
            promptTokens=record.prompt_tokens,
            completionTokens=record.completion_tokens,
            finishReason=str(finish_reason or "stop"),
            latencyMs=latency_ms,
        )
        return ChatResponse(
            text=text,
            finish_reason=str(finish_reason or "stop"),
            usage=record,
        )

    # ------------------------------------------------------------ chat 流式

    async def stream_chat(
        self,
        messages: list[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[ChatStreamChunk]:
        """流式对话：按 SSE 逐块产出增量。

        三处刻意的处理：
        - **拿不到 `stream_options` 也不报错**：不是每个 OpenAI 兼容服务都支持它，
          于是用量可能缺席（`usage is None`）—— 宁可少报 Token，也不要编。
          同时把「带 stream_options 被 400 拒绝」当成可重试：去掉它再试一次。
        - **缓冲区按行切**：TCP 分片会把一行 JSON 劈成两半，必须把残行留到下一块。
        - **异常路径也要关连接**：`async with` 保证上游不被挂住；否则一次 400
          就会留下一个永远不释放的连接与一个还在生成的请求。
        """
        self._require("chat")
        payload: dict[str, Any] = {
            "model": self._config.model,
            "messages": [message.to_payload() for message in messages],
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        resolved_temperature = self._resolve(temperature, self._config.temperature)
        if resolved_temperature is not None:
            payload["temperature"] = resolved_temperature
        resolved_max_tokens = max_tokens or self._config.max_tokens
        if resolved_max_tokens is not None:
            payload["max_tokens"] = resolved_max_tokens

        started = time.perf_counter()
        try:
            async for chunk in self._stream("/chat/completions", payload):
                yield chunk
        except ProviderError as first:
            # 上游不认 stream_options：这是「多要了一个可选字段」，不是配置错误。
            # 只在带上了它的时候重试一次，避免把真正的 400 也重试成两次调用。
            if "stream_options" not in payload:
                raise
            logger.info("上游拒绝 stream_options，改为不带用量重试一次：%s", first)
            payload.pop("stream_options", None)
            async for chunk in self._stream("/chat/completions", payload):
                yield chunk
        logger.debug(
            "stream_chat 结束：model=%s elapsedMs=%d", self._config.model, _elapsed_ms(started)
        )

    async def _stream(self, path: str, payload: dict[str, Any]) -> AsyncIterator[ChatStreamChunk]:
        """真正的 SSE 解析：把 `data: {...}` 逐行翻成增量块。"""
        async with self._client.stream("POST", path, json=payload) as response:
            if response.status_code >= 400:
                body = (await response.aread()).decode("utf-8", errors="replace")
                self._raise_for_status(response.status_code, body)

            buffer = ""
            async for raw in response.aiter_text():
                buffer += raw
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    chunk = _parse_stream_line(line.strip())
                    if chunk is _STREAM_DONE:
                        return
                    # 用「是不是真块」判断而不是 `is not _STREAM_SKIP`：
                    # 后者的否定式收窄 mypy 不认（`is` 只对单例枚举收窄），会一路报 yield 类型不符
                    if isinstance(chunk, ChatStreamChunk):
                        yield chunk
            tail = _parse_stream_line(buffer.strip())
            if isinstance(tail, ChatStreamChunk):
                yield tail

    def _raise_for_status(self, status_code: int, body: str) -> None:
        """与 `_post` 同一套映射：失败形态必须一致，否则流式与非流式会在同一故障下给出不同结论。"""
        del body  # 上游报文可能含隐私，不写进异常与日志
        _record_model_failure(self._config, status_code)
        if status_code in (401, 403):
            raise ProviderAuthError("模型密钥无效或无权限，请检查面板里的 API Key")
        if status_code == 429:
            raise ProviderRateLimitError("模型服务限流，请稍后重试")
        if status_code >= 500:
            raise ProviderUnavailableError(f"模型服务返回 {status_code}")
        raise ProviderError(
            f"模型服务拒绝了请求（HTTP {status_code}）",
            detail="常见原因：模型名不存在或不支持流式参数",
        )

    # ----------------------------------------------------------- embedding

    async def embed(self, texts: list[str]) -> EmbeddingResponse:
        self._require("embedding")
        if not texts:
            raise ProviderError("嵌入输入不能为空")

        started = time.perf_counter()
        data = await self._post("/embeddings", {"model": self._config.model, "input": texts})
        latency_ms = _elapsed_ms(started)

        raw_items = data.get("data")
        if not isinstance(raw_items, list) or len(raw_items) != len(texts):
            # 少一条或多一条都会让「向量 ↔ 原文」错位，宁可失败也不要错位
            actual = len(raw_items) if isinstance(raw_items, list) else "n/a"
            raise ProviderUnavailableError(
                "嵌入结果数量与输入不一致",
                detail=f"expected={len(texts)}, got={actual}",
            )
        vectors: list[list[float]] = []
        for item in raw_items:
            embedding = item.get("embedding") if isinstance(item, dict) else None
            if not isinstance(embedding, list):
                raise ProviderUnavailableError("嵌入响应缺少 embedding 字段")
            vectors.append([float(value) for value in embedding])

        dimension = len(vectors[0]) if vectors else 0
        if self._config.dimension is not None and dimension != self._config.dimension:
            # 维度不一致会让 Qdrant 直接拒绝写入：这里提前给出可操作的错误
            raise ProviderUnavailableError(
                f"嵌入维度与配置不符：配置 {self._config.dimension}，实际 {dimension}",
                detail="换嵌入模型必须同步重建集合维度",
            )

        usage = _as_mapping(data.get("usage"))
        record_event(
            "model",
            call="embed",
            role=self._config.role,
            model=self._config.model,
            inputs=len(texts),
            dimension=dimension,
            latencyMs=latency_ms,
        )
        return EmbeddingResponse(
            vectors=vectors,
            dimension=dimension,
            usage=TokenUsage.of(
                _as_int(usage.get("prompt_tokens")) or _as_int(usage.get("total_tokens")),
                0,
                latency_ms=latency_ms,
                model=self._config.model,
            ),
        )

    # -------------------------------------------------------------- rerank

    async def rerank(
        self,
        query: str,
        documents: list[str],
        *,
        top_n: int | None = None,
    ) -> RerankResponse:
        self._require("rerank")
        if not documents:
            raise ProviderError("重排候选不能为空")

        payload: dict[str, Any] = {
            "model": self._config.model,
            "query": query,
            "documents": documents,
        }
        if top_n is not None:
            payload["top_n"] = top_n

        started = time.perf_counter()
        data = await self._post("/rerank", payload)
        latency_ms = _elapsed_ms(started)

        raw_items = data.get("results")
        if not isinstance(raw_items, list):
            raise ProviderUnavailableError("重排响应缺少 results 字段")
        results: list[RerankResult] = []
        for item in raw_items:
            if not isinstance(item, dict):
                continue
            index = _as_int(item.get("index"))
            if index is None or index < 0 or index >= len(documents):
                # 越界下标会让引用指向错误的候选，必须拦
                raise ProviderUnavailableError("重排返回的候选下标越界", detail=f"index={index}")
            score = float(item.get("relevance_score") or 0.0)
            results.append(RerankResult(index=index, score=score))

        usage = _as_mapping(data.get("usage"))
        record_event(
            "model",
            call="rerank",
            role=self._config.role,
            model=self._config.model,
            documents=len(documents),
            results=len(results),
            latencyMs=latency_ms,
        )
        return RerankResponse(
            results=results,
            usage=TokenUsage.of(
                _as_int(usage.get("prompt_tokens")) or _as_int(usage.get("total_tokens")),
                0,
                latency_ms=latency_ms,
                model=self._config.model,
            ),
        )

    # ------------------------------------------------------------- 内部

    def _require(self, capability: str) -> None:
        if not self._caps.supports(capability):
            raise UnsupportedCapabilityError(
                f"该 Provider 不支持 {capability}（当前能力：{self._caps.describe()}）",
                detail=f"role={self._config.role}",
            )

    @staticmethod
    def _resolve(explicit: float | None, configured: float | None) -> float | None:
        return explicit if explicit is not None else configured

    async def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        try:
            response = await self._client.post(path, json=payload)
        except httpx.TimeoutException as exc:
            raise ProviderTimeoutError("模型服务响应超时") from exc
        except httpx.HTTPError as exc:
            raise ProviderUnavailableError("无法连接模型服务") from exc

        if response.status_code in (401, 403):
            raise ProviderAuthError("模型密钥无效或无权限，请检查面板里的 API Key")
        if response.status_code == 429:
            raise ProviderRateLimitError("模型服务限流，请稍后重试")
        if response.status_code >= 500:
            raise ProviderUnavailableError(f"模型服务返回 {response.status_code}")
        if response.status_code >= 400:
            # 400 多为模型名写错、参数不被支持：属于配置问题，不可重试
            _record_model_failure(self._config, response.status_code)
            raise ProviderError(
                f"模型服务拒绝了请求（HTTP {response.status_code}）",
                detail="常见原因：模型名不存在或不支持该参数",
            )
        try:
            data = response.json()
        except ValueError as exc:
            raise ProviderUnavailableError("模型服务返回的不是合法 JSON") from exc
        if not isinstance(data, dict):
            raise ProviderUnavailableError("模型服务返回结构异常")
        return data


def _record_model_failure(config: ProviderConfig, status_code: int) -> None:
    """失败也进链路事件：这正是「一次 429 让整行指标归零」那类事故最需要的线索。

    **只记状态码，不记报文**：上游报文可能回显用户内容。
    """
    record_event(
        "model",
        call="failed",
        role=config.role,
        model=config.model,
        status=status_code,
    )


def _first(choices: Any) -> Any:
    return choices[0] if isinstance(choices, list) and choices else {}


#: `data: [DONE]` 与「这一行没有内容」的哨兵。
#: 用类实例而不是 `object()`：`object` 会把返回类型污染成 `object | ChatStreamChunk`，
#: mypy 随后在 `yield` 处报错，而这里本可以用类型系统把三种结果分清楚。
class _StreamSentinel:
    __slots__ = ("reason",)

    def __init__(self, reason: str) -> None:
        self.reason = reason

    def __repr__(self) -> str:  # pragma: no cover - 只用于排障输出
        return f"<stream {self.reason}>"


_STREAM_DONE = _StreamSentinel("done")
_STREAM_SKIP = _StreamSentinel("skip")


def _parse_stream_line(line: str) -> ChatStreamChunk | _StreamSentinel:
    """解析一行 SSE。

    返回哨兵表示「结束」或「这行没有增量」（心跳、事件名、空行、解析失败的垃圾行），
    否则返回一个增量块。**解析失败的行一律跳过**：部分服务会在流里插注释或非 JSON 心跳，
    为它们整条流中断，比丢掉一行没用的事件更糟。
    """
    if not line or line.startswith(":"):
        return _STREAM_SKIP
    if line.startswith("data:"):
        line = line[len("data:") :].strip()
    if not line:
        return _STREAM_SKIP
    if line == "[DONE]":
        return _STREAM_DONE
    try:
        data = json.loads(line)
    except ValueError:
        return _STREAM_SKIP
    if not isinstance(data, dict):
        return _STREAM_SKIP

    choices = data.get("choices")
    choice = _first(choices)
    text = ""
    finish_reason: str | None = None
    if isinstance(choice, dict):
        delta = choice.get("delta")
        if isinstance(delta, dict):
            content = delta.get("content")
            # 有的服务在 `delta.content` 给 null（只发 role 或只发 tool_calls）
            text = content if isinstance(content, str) else ""
        raw_reason = choice.get("finish_reason")
        finish_reason = str(raw_reason) if raw_reason else None

    usage = _as_mapping(data.get("usage"))
    if not text and finish_reason is None and not usage:
        # 例如只带 role 的首块：没有内容也没有结束标记，交给上层忽略
        return _STREAM_SKIP

    parsed_usage = None
    if usage:
        parsed_usage = TokenUsage.of(
            _as_int(usage.get("prompt_tokens")),
            _as_int(usage.get("completion_tokens")),
            latency_ms=0,
            model=None,
        )
    return ChatStreamChunk(text=text, finish_reason=finish_reason, usage=parsed_usage)


def _as_mapping(value: Any) -> dict[str, Any]:
    """把上游 JSON 里的对象字段收成 ``dict[str, Any]``。

    直接写 `data.get("usage") if isinstance(..., dict) else {}` 时，
    mypy 会把类型收窄成 `Any | dict[Any, Any] | None`，后续 `.get` 反而报错；
    收口成一个辅助函数既让类型确定，也统一了「字段缺失时给空字典」的语义。
    """
    if isinstance(value, dict):
        return {str(key): item for key, item in value.items()}
    return {}


def _as_int(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _elapsed_ms(started: float) -> int:
    return max(0, int((time.perf_counter() - started) * 1000))
