"""`maxSamples` 显式给 null 时，必须当作「用默认值」。

这条回归网守的是一个**真实发生过两轮排查**的故障：Java 侧（`AiWritingController.style()`）
把前端的 `maxSamples` 原样转发，前端没给时就是 `null`；而 Pydantic 的 `default=20`
只在字段**缺失**时生效，**显式 null 会被判成类型错误** →

    {"detail":[{"loc":["body","maxSamples"],"msg":"Input should be a valid integer"}]}

再被 Feign 包成 `HTTP 200 + code 500「系统繁忙」`，真正的原因完全看不见。

口径（与 Java 侧那条用例一致）：**默认值只存在一处（Python）**，
Java 不替它编值；所以 Python 必须接受 null 并把它当作「没给」。
"""

from __future__ import annotations

from app.schemas.style import WritingStyleRequest


def test_null_max_samples_means_default() -> None:
    """显式 null = 没给 = 用默认（20），而不是 422。"""
    request = WritingStyleRequest.model_validate({"authorId": 7, "maxSamples": None})
    assert request.max_samples == 20


def test_absent_max_samples_means_default() -> None:
    """字段缺失同样是默认值（这条早就成立，一起钉住免得改坏）。"""
    request = WritingStyleRequest.model_validate({"authorId": 7})
    assert request.max_samples == 20


def test_explicit_value_is_kept() -> None:
    """真的给了值就按给的值走（别把用户的意图也吞掉）。"""
    request = WritingStyleRequest.model_validate({"authorId": 7, "maxSamples": 5})
    assert request.max_samples == 5
