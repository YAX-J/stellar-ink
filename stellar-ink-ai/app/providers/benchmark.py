"""Provider 压测的统计核心（M6）。

为什么统计要单独成模块、而不是写在脚本里：**压测最容易出的错不是测不准，而是报错的东西**
—— 把「没测到」写成 0、把「失败请求」算进延迟、把非流式的首字延迟编一个出来。
这些数字一旦进了报告，就会被人拿去比较（「本地比云端快 3 倍」），而它们本来是无意义的。
所以这里把口径钉死，并由测试盯着：

1. **失败请求不参与延迟统计**，但**必须单独计数**（失败了一次 30 秒超时混进 P95，
   会让整个延迟分布看起来变差，而真相是「有一次根本没成功」）；
2. **TTFT 只有流式才测得到**：非流式调用首字与整段是同一个时间点，
   编一个「TTFT ≈ 总延迟」是最常见的假数据 —— 这里回 `None` 并说明原因；
3. **空样本不给数**：`p50=None` 而不是 0（0 会被读成「快得不可思议」）；
4. **百分位用最近秩法**（nearest-rank）：样本量小时线性插值会造出「比任何一次实测都快」的数字。
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True, slots=True)
class Sample:
    """一次调用的观测。"""

    latency_ms: float
    ok: bool
    #: 首字延迟：**只有流式调用才有**（非流式留 None，别编）
    ttft_ms: float | None = None
    completion_tokens: int = 0
    prompt_tokens: int = 0
    error: str = ""


@dataclass(slots=True)
class BenchReport:
    """一个角色的压测结果。"""

    role: str
    requests: int
    concurrency: int
    ok_count: int = 0
    failed_count: int = 0
    latency_p50_ms: float | None = None
    latency_p95_ms: float | None = None
    latency_max_ms: float | None = None
    ttft_p50_ms: float | None = None
    tokens_per_second: float | None = None
    requests_per_second: float | None = None
    wall_ms: float = 0.0
    errors: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    #: 显存/内存：拿不到就留 None（**不填 0**，0 会被读成「不占显存」）
    vram_mb: float | None = None
    vram_note: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "role": self.role,
            "requests": self.requests,
            "concurrency": self.concurrency,
            "okCount": self.ok_count,
            "failedCount": self.failed_count,
            "latencyP50Ms": self.latency_p50_ms,
            "latencyP95Ms": self.latency_p95_ms,
            "latencyMaxMs": self.latency_max_ms,
            "ttftP50Ms": self.ttft_p50_ms,
            "tokensPerSecond": self.tokens_per_second,
            "requestsPerSecond": self.requests_per_second,
            "wallMs": round(self.wall_ms, 2),
            "vramMb": self.vram_mb,
            "vramNote": self.vram_note,
            "errors": self.errors,
            "notes": self.notes,
        }


def percentile(values: Sequence[float], quantile: float) -> float | None:
    """最近秩法百分位。

    「最近秩」= 取第 ceil(q × n) 个（从 1 数），也就是**真实出现过的某个值**。
    样本少的时候（压测常常只有 20 次）线性插值会造出「比任何一次实测都快」的数字，
    而那种数字最容易被当成「优化有效」。
    """
    if not values:
        return None
    if not 0 < quantile <= 1:
        raise ValueError("quantile 必须在 (0, 1] 之间")
    ordered = sorted(values)
    rank = max(1, int(-(-len(ordered) * quantile // 1)))  # ceil 不用 math 模块
    return ordered[min(rank, len(ordered)) - 1]


def summarize(
    role: str,
    samples: Iterable[Sample],
    *,
    requests: int,
    concurrency: int,
    wall_ms: float,
) -> BenchReport:
    """把一批观测汇成一份报告，并把「没测到的东西」如实标出来。"""
    items = list(samples)
    ok = [item for item in items if item.ok]
    failed = [item for item in items if not item.ok]

    report = BenchReport(role=role, requests=requests, concurrency=concurrency, wall_ms=wall_ms)
    report.ok_count = len(ok)
    report.failed_count = len(failed)
    report.errors = sorted({item.error for item in failed if item.error})[:5]

    if not items:
        report.notes.append("一次都没跑起来 —— 这不算「很快」，而是「没测到」。")
        return report

    # ⚠️ 延迟只统计**成功**的那些：一次 30 秒超时混进 P95 会让整个分布看起来变差，
    # 而真相是「有一次根本没成功」——那是另一个指标（失败率）
    latencies = [item.latency_ms for item in ok]
    report.latency_p50_ms = _round(percentile(latencies, 0.5))
    report.latency_p95_ms = _round(percentile(latencies, 0.95))
    report.latency_max_ms = _round(max(latencies)) if latencies else None

    ttfts = [item.ttft_ms for item in ok if item.ttft_ms is not None]
    report.ttft_p50_ms = _round(percentile(ttfts, 0.5))
    if not ttfts:
        report.notes.append(
            "没测到首字延迟（TTFT）：只有**流式**调用才有首字时刻，"
            "非流式把首字与整段算成同一个时间点 —— 那种「TTFT」是编出来的，所以这里留空。"
        )

    completion_tokens = sum(item.completion_tokens for item in ok)
    generation_ms = sum(item.latency_ms - (item.ttft_ms or 0.0) for item in ok)
    if completion_tokens > 0 and generation_ms > 0:
        report.tokens_per_second = round(completion_tokens / (generation_ms / 1000), 2)
    elif completion_tokens == 0:
        report.notes.append("没有 completion tokens（嵌入/重排这类接口本来就不产出 token）。")

    if wall_ms > 0:
        report.requests_per_second = round(len(ok) / (wall_ms / 1000), 2)

    if failed:
        report.notes.append(
            f"{len(failed)} 次失败**没有**计入延迟分位（延迟只统计成功的调用）——"
            "但失败率要单独看：它高了，延迟再好看也没意义。"
        )
    return report


def detect_vram_mb() -> tuple[float | None, str]:
    """尽力读一次显存占用。

    拿不到就返回 `(None, 原因)`：**不填 0** —— 「0 MB」会被读成「这个模型不吃显存」，
    而真相是「我们没看到 GPU」。
    """
    import shutil
    import subprocess

    executable = shutil.which("nvidia-smi")
    if executable is None:
        return None, "未检测到 nvidia-smi（本机没有可见的 NVIDIA GPU，或没装驱动）"
    try:
        # 用 `which` 出来的**绝对路径**：部分环境下 PATH 里的条目可被替换，
        # 而压测脚本会以运维身份跑在有 GPU 的机器上
        output = subprocess.run(  # noqa: S603 - 命令是固定列表（which 出来的绝对路径 + 固定参数），无外部输入
            [executable, "--query-gpu=memory.used", "--format=csv,noheader,nounits"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        return None, f"nvidia-smi 调用失败：{error}"
    if output.returncode != 0:
        return None, f"nvidia-smi 返回 {output.returncode}：{output.stderr.strip()[:80]}"
    values = [line.strip() for line in output.stdout.splitlines() if line.strip()]
    if not values:
        return None, "nvidia-smi 没有返回显存数据"
    try:
        total = sum(float(value) for value in values)
    except ValueError:
        return None, f"nvidia-smi 输出无法解析：{values[:2]}"
    return total, f"全部 GPU 合计（{len(values)} 张）"


def _round(value: float | None) -> float | None:
    return None if value is None else round(value, 2)
