"""Cloudflare Clef 的 typed questions 协议，不把决策概率当成聊天正文。"""

from __future__ import annotations

import json
import math
import re
import time
from dataclasses import dataclass
from typing import Any

import httpx

from app.core.trace import record_event
from app.providers.errors import ProviderError, ProviderUnavailableError
from app.providers.models import TokenUsage
from app.providers.openai_compatible import OpenAICompatibleProvider
from app.providers.usage import metered


@dataclass(frozen=True)
class DecisionChoice:
    choice: str
    probabilities: dict[str, float]


@dataclass(frozen=True)
class DecisionResponse:
    answers: dict[str, DecisionChoice]
    usage: TokenUsage


class CloudflareDecisionProvider(OpenAICompatibleProvider):
    """复用 HTTP 超时、退避、熔断与脱敏；能力闸门禁止调用 chat/embedding/rerank。"""

    @metered
    async def decide(self, state: dict[str, Any], questions: dict[str, Any]) -> DecisionResponse:
        self._require("decision")
        model = self.config.model
        if not re.fullmatch(r"@cf/[\w-]+/[\w-]+", model):
            raise ProviderError("请填写完整 Workers AI 模型标识，如 @cf/cloudflare/clef-flash")
        payload = {"model": model.rsplit("/", 1)[-1], "state": state, "questions": questions}
        # 以 UTF-8 字节上界保守控制上下文，避免服务端截断复核材料后仍给出分数。
        if len(json.dumps(payload, ensure_ascii=False).encode("utf-8")) > 20_000:
            raise ValueError("复核材料超过保守上下文预算，请改由人工复核本题")
        started = time.perf_counter()
        envelope = await self._post(self._decision_endpoint(), payload)
        data = envelope.get("result", envelope)
        if envelope.get("success") is False or not isinstance(data, dict):
            raise ProviderUnavailableError("Cloudflare 决策请求失败，请检查 Workers AI 权限与配置")
        try:
            raw_answers = data["answers"]
            answers: dict[str, DecisionChoice] = {}
            if not isinstance(raw_answers, dict) or set(raw_answers) != set(questions):
                raise ValueError("判定项不完整")
            for key, question in questions.items():
                raw = raw_answers[key]
                probabilities = raw["probabilities"]
                if raw["type"] != "choice" or set(probabilities) != set(question["criteria"]):
                    raise ValueError("选项不匹配")
                if any(
                    isinstance(p, bool)
                    or not isinstance(p, (float, int))
                    or not math.isfinite(p)
                    or not 0 <= p <= 1
                    for p in probabilities.values()
                ):
                    raise ValueError("概率非法")
                if abs(sum(probabilities.values()) - 1) > 0.02:
                    raise ValueError("概率和不为一")
                choice = raw["choice"]
                if (
                    choice not in probabilities
                    or probabilities[choice] < max(probabilities.values()) - 1e-6
                ):
                    raise ValueError("所选项不匹配")
                answers[key] = DecisionChoice(choice, probabilities)
            usage = data["usage"]
            prompt = usage["input_tokens"]
            completion = usage["output_tokens"]
            if any(type(v) is not int or v < 0 for v in (prompt, completion)):
                raise ValueError("用量非法")
        except (KeyError, TypeError, ValueError, AttributeError) as error:
            raise ProviderUnavailableError(
                "Cloudflare 返回的决策结构不完整，未保存复核结果"
            ) from error
        measured = TokenUsage.of(
            prompt, completion, latency_ms=int((time.perf_counter() - started) * 1000), model=model
        )
        record_event(
            "model",
            call="decision",
            role=self.config.role,
            model=model,
            promptTokens=prompt,
            completionTokens=completion,
            latencyMs=measured.latency_ms,
        )
        return DecisionResponse(answers, measured)

    def _decision_endpoint(self) -> str:
        """兼容 /ai 根、/ai/run 及完整模型端点，保留用户配置的主机与代理前缀。"""
        base = httpx.URL(self.config.base_url)
        path = base.path.rstrip("/")
        suffix = f"/run/{self.config.model}"
        if path.endswith(suffix):
            return str(base.copy_with(path=path))
        if path.endswith("/run"):
            return str(base.copy_with(path=f"{path}/{self.config.model}"))
        return f"/run/{self.config.model}"
