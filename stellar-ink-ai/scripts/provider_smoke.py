"""Provider 真实冒烟：对每个已配置的角色**各打一次真实调用**，证明「配了」≠「能用」。

为什么必须有这条脚本：面板里的「测试连接」是 `scope: tcp_only` —— 它只证明**端点可达**，
不验证密钥、模型名与路径拼接。所以它会给出一种最误导人的绿色。

实测踩到的那次：`rerank` 角色的 `base_url` 被填成了完整端点
（`https://openrouter.ai/api/v1/rerank`），而按约定代码还会再拼一次 `/rerank`
（见 `app/providers/openai_compatible.py` 的 `_post("/rerank", ...)`），
于是真实请求打到 `/api/v1/rerank/rerank` → **404 text/plain**。
症状是「模型服务拒绝了请求（HTTP 404）…常见原因：模型名不存在」——
指向模型名，而真因是多写了一截路径。面板对此**一声不响**。

这条脚本读的就是应用读的那份配置（`app.providers.runtime.registry()`，唯一解析器），
所以它同时验证四件事：配置读得到 / 密钥解得开 / 路径拼得对 / 模型确实存在。

用法::

    uv run python scripts/provider_smoke.py                     # 检查全部已配置角色
    uv run python scripts/provider_smoke.py --role rerank       # 只查一个（可重复）

读的是哪份配置由 `MYSQL_*` / `AI_PROVIDER_CONFIG_JSON` 决定（见 `describe_sources()` 的输出）。
⚠️ 本机默认经 `stellar-ink-ai/.env` 读库；要读本机库就像 `start-all.bat` 那样显式给
`MYSQL_HOST=127.0.0.1`。

判定口径：
- 通过 = 真实往返拿到了**可用结果**（不是「没报错」）；
- 退出码 0 仅当被检查的角色**全部通过**；任何一个失败即非 0。
- 不打印密钥（只打印 `safe_summary()` 与指纹），也不回显上游原文。
"""

from __future__ import annotations

import argparse
import asyncio
import time
from collections.abc import Sequence
from dataclasses import dataclass, field

from app.providers import runtime
from app.providers.config_source import describe_sources
from app.providers.errors import ProviderError
from app.providers.models import ChatMessage, MessageRole

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console

#: 嵌入探针：两段**必须不同**。相同文本拿到相同向量证明不了任何事
#: （有些坏实现会对所有输入返回同一个常量向量 —— 那种情况下面的一致性检查会点出来）。
EMBED_TEXTS = (
    "星笺是一本把文章比作星辰的夜间写作博客。",
    "这段文字与上一段毫无关系，用来确认批量嵌入是分别编码的。",
)

#: 重排探针：第 0 篇与问题明显相关，另两篇明显无关。序对了才能说重排真的在工作。
RERANK_QUERY = "星笺这个博客的设计基调是什么？"
RERANK_DOCS = (
    "星笺的设计基调是深色星空、缓慢、诗意，任何改动都不得破坏这个气质。",
    "本段讨论的是 MySQL 索引失效的几种常见写法。",
    "本段介绍在 Windows 上把 Redis 装成开机自启服务。",
)
RERANK_EXPECTED_TOP = 0

#: 两段嵌入的余弦高于这个值就可疑：不同内容被编码成了几乎同一个向量
SUSPICIOUS_COSINE = 0.999


@dataclass(slots=True)
class Probe:
    """一个角色的探针结论。`detail` 给人看，`warnings` 是「通过但有话说」。"""

    role: str
    model: str
    ok: bool
    detail: str
    warnings: list[str] = field(default_factory=list)


def _cosine(left: Sequence[float], right: Sequence[float]) -> float:
    """余弦相似度；维度不等或零向量返回 0（探针不该在这里崩）。"""
    if len(left) != len(right) or not left:
        return 0.0
    # 显式累加而不是 `sum(...)`：后者的类型推断在这里退化成 Any，
    # 于是「返回 float」的函数被 mypy 判成 no-any-return（累加版本没有这个歧义）。
    dot = 0.0
    norm_left = 0.0
    norm_right = 0.0
    for a, b in zip(left, right, strict=True):
        dot += a * b
        norm_left += a * a
        norm_right += b * b
    if norm_left == 0.0 or norm_right == 0.0:
        return 0.0
    return dot / (norm_left**0.5 * norm_right**0.5)


async def _probe_chat(role: str, model: object) -> Probe:
    """对话探针：**沿用该角色配置里的 `max_tokens`**，不另给一个宽松值。

    这一点是刻意的：`max_tokens` 给小了的表现是「content 为空 + finish_reason=length」，
    而接推理模型时那正是最容易踩的坑（思考也占 completion_tokens）。
    用配置里的预算去问，才能把「这个角色的预算够不够」一起验出来。
    """
    chat = model
    started = time.perf_counter()
    response = await chat.chat(  # type: ignore[attr-defined]
        [
            ChatMessage(MessageRole.SYSTEM, "你是一个连通性自检探针，回答务必极短。"),
            ChatMessage(MessageRole.USER, "只回两个字：收到"),
        ]
    )
    elapsed = int((time.perf_counter() - started) * 1000)
    usage = response.usage

    warnings: list[str] = []
    if response.truncated:
        warnings.append(
            f"被 max_tokens 截断（当前配置 {_configured_max_tokens(chat)}）："
            "推理模型的思考也占 completion_tokens，建议该角色调到 2048 以上"
        )
    if usage.model and usage.model != getattr(getattr(chat, "config", None), "model", None):
        warnings.append(f"上游回报的模型名是 {usage.model}，与配置的模型名不一致")

    if response.refused and not response.truncated:
        return Probe(role, _model_of(chat), False, "模型正常结束但一个字都没产出（拒答）", warnings)
    if response.empty:
        return Probe(role, _model_of(chat), False, "响应内容为空", warnings)

    detail = (
        f"{elapsed}ms，返回 {len(response.text)} 字，"
        f"tokens={usage.prompt_tokens}+{usage.completion_tokens}，finish={response.finish_reason}"
    )
    return Probe(role, _model_of(chat), True, detail, warnings)


async def _probe_embedding(role: str, model: object) -> Probe:
    """嵌入探针：看**条数、维度、耗时**，并确认两段不同文本没有被编码成同一个向量。"""
    embedder = model
    started = time.perf_counter()
    response = await embedder.embed(list(EMBED_TEXTS))  # type: ignore[attr-defined]
    elapsed = int((time.perf_counter() - started) * 1000)

    warnings: list[str] = []
    configured = getattr(getattr(embedder, "config", None), "dimension", None)
    if configured is None:
        warnings.append(
            f"该角色没填 dimension（实际 {response.dimension}）："
            "离线评测不填也能跑，但建 Qdrant 集合时要用这个维度"
        )

    if len(response.vectors) < 2:
        return Probe(role, _model_of(embedder), False, "返回的向量条数少于输入条数", warnings)
    cosine = _cosine(response.vectors[0], response.vectors[1])
    if cosine > SUSPICIOUS_COSINE:
        return Probe(
            role,
            _model_of(embedder),
            False,
            f"两段不同文本的余弦是 {cosine:.4f}（>{SUSPICIOUS_COSINE}）："
            "这个端点没有真的在编码内容",
            warnings,
        )

    detail = (
        f"{elapsed}ms，{len(response.vectors)} 条 / {response.dimension} 维，"
        f"两段相似度 {cosine:.3f}"
    )
    return Probe(role, _model_of(embedder), True, detail, warnings)


async def _probe_rerank(role: str, model: object) -> Probe:
    """重排探针：确认真的返回了**有序的分数**，并看一眼序对不对。"""
    reranker = model
    started = time.perf_counter()
    response = await reranker.rerank(RERANK_QUERY, list(RERANK_DOCS))  # type: ignore[attr-defined]
    elapsed = int((time.perf_counter() - started) * 1000)

    if not response.results:
        return Probe(role, _model_of(reranker), False, "返回了空的 results")

    scores = [round(result.score, 4) for result in response.results]
    warnings: list[str] = []
    if len(set(scores)) == 1:
        warnings.append(f"所有候选拿到同一个分数 {scores[0]}：这个端点没有真的在打分")
    top = response.results[0].index
    if top != RERANK_EXPECTED_TOP:
        # 语义上是「明显相关的那篇没有排第一」。判成警告而非失败：
        # 免费/小模型的排序质量本就有限，而这条脚本要守的是「链路通不通」。
        warnings.append(
            f"第 {RERANK_EXPECTED_TOP} 篇与问题明显相关，但它没排第一（第一是第 {top} 篇）"
        )

    detail = f"{elapsed}ms，{len(response.results)} 条候选，首名=#{top}，分数={scores}"
    return Probe(role, _model_of(reranker), True, detail, warnings)


def _model_of(model: object) -> str:
    return str(getattr(getattr(model, "config", None), "model", None) or "unknown")


def _configured_max_tokens(model: object) -> object:
    return getattr(getattr(model, "config", None), "max_tokens", None)


#: 角色 → 探针。`chat` 系三个角色共用对话探针（能力都是 chat）
_PROBES = {
    "chat": _probe_chat,
    "fast": _probe_chat,
    "reasoning": _probe_chat,
    "embedding": _probe_embedding,
    "rerank": _probe_rerank,
}


async def run(roles: Sequence[str] | None = None) -> int:
    """跑冒烟并返回退出码（0 = 全部通过）。"""
    try:
        registry = runtime.registry()
    except Exception as error:  # noqa: BLE001 - 配置读不出来时要给可读结论，而不是栈
        print(f"✗ 读不到模型配置：{error}")
        print(f"  来源：{describe_sources()}")
        return 1

    configured = registry.roles
    print(f"配置来源：{describe_sources()}")
    print(f"已配置角色：{'、'.join(configured) if configured else '(空)'}")
    if not configured:
        print("✗ 一条配置都没有：先在「AI 实验室 → 模型配置」里填一个角色")
        return 1

    targets = list(roles) if roles else configured
    unknown = [role for role in targets if role not in _PROBES]
    if unknown:
        print(f"✗ 未知角色：{'、'.join(unknown)}（可选：{'/'.join(sorted(_PROBES))}）")
        return 1

    probes: list[Probe] = []
    for role in targets:
        config = registry.config_of(role)
        if config is None:
            probes.append(Probe(role, "-", False, "该角色尚未配置模型", []))
            continue
        summary = config.safe_summary()
        details = f"能力 {summary['capabilities']}｜指纹 {summary['fingerprint']}"
        print(f"\n— {role}｜{summary['model']}｜{details}")
        try:
            probe = await _PROBES[role](role, _instance_for(registry, role))
        except ProviderError as error:
            # ProviderError 的 message 已经是可以直接给用户看的话（不含上游原文与密钥）
            probes.append(Probe(role, str(summary["model"]), False, str(error), []))
            continue
        except Exception as error:  # noqa: BLE001 - 探针本身不该把整轮冒烟打断
            probes.append(
                Probe(role, str(summary["model"]), False, f"{type(error).__name__}: {error}", [])
            )
            continue
        probes.append(probe)

    await registry.aclose()

    print("\n== 结论 ==")
    for probe in probes:
        mark = "✓" if probe.ok else "✗"
        print(f"{mark} {probe.role:<10} {probe.model}")
        print(f"    {probe.detail}")
        for warning in probe.warnings:
            print(f"    ⚠ {warning}")

    failed = [probe for probe in probes if not probe.ok]
    print(f"\n通过 {len(probes) - len(failed)}/{len(probes)}")
    return 1 if failed else 0


def _instance_for(registry: object, role: str) -> object:
    """按角色取实例：走 registry 的三个具名 getter，能力不符会在**取用时**就报错。"""
    if role in {"chat", "fast", "reasoning"}:
        return registry.chat_model(role)  # type: ignore[attr-defined]
    if role == "embedding":
        return registry.embedding_model(role)  # type: ignore[attr-defined]
    return registry.rerank_model(role)  # type: ignore[attr-defined]


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="对已配置的模型角色各打一次真实调用")
    parser.add_argument(
        "--role",
        action="append",
        dest="roles",
        help="只检查指定角色（可重复）；默认检查全部已配置角色",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    use_utf8_console()
    args = parse_args(argv)
    return asyncio.run(run(args.roles))


if __name__ == "__main__":
    raise SystemExit(main())
