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

import time
from typing import Any

import httpx

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
    EmbeddingResponse,
    ProviderCapabilities,
    ProviderConfig,
    RerankResponse,
    RerankResult,
    TokenUsage,
)


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
        return ChatResponse(
            text=text,
            finish_reason=str(finish_reason or "stop"),
            usage=TokenUsage.of(
                _as_int(usage.get("prompt_tokens")),
                _as_int(usage.get("completion_tokens")),
                latency_ms=latency_ms,
                model=self._config.model,
            ),
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


def _first(choices: Any) -> Any:
    return choices[0] if isinstance(choices, list) and choices else {}


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
