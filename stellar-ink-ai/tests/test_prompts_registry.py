"""提示词注册表（M8，`app/prompts/`）。

这一层要守的四件事：

1. **模板只有一份**：注册表登记的就是各模块正在用的那个模板（不是抄一份），
   所以「注册表里写着 v1」与「跑起来用的是 v1」是同一件事；
2. **变量显式且能自检**：声明的变量与模板里实际用到的必须一致 ——
   否则一渲染就炸，而且常常炸成一个与真实原因无关的 KeyError（E4 踩过）；
3. **JSON 花括号不是字段名**：提示词里常有 JSON 示例，那些花括号在 `str.format`
   眼里就是字段。所以「原样发送」的提示词标 `interpolated=False`，自检不去解析它；
4. **评测状态如实留白**：没评测过的就是 `None`，接口里是 `null` ——
   编一个「效果良好」比留白危险得多。
"""

from __future__ import annotations

import json

import pytest
from fastapi import FastAPI

from app.main import create_app
from app.prompts import build_registry, registry, registry_problems
from app.prompts.registry import PromptError, PromptRegistry, PromptSpec, declared_variables
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers


def test_registry_self_check_passes_on_the_real_catalog() -> None:
    """真实目录必须自检通过：声明与模板实际变量对不上，运行时就会炸。"""
    assert registry_problems() == []


def test_catalog_covers_the_prompts_we_actually_send() -> None:
    """注册表要覆盖真正在用的提示词（漏一个就等于「有版本可查」是假的）。"""
    names = set(registry().names())

    assert {
        "qa.system",
        "qa.memory",
        "wiki.claims",
        "memory.candidates",
        "writing.suggest",
        "agent.system",
    } <= names


def test_registered_template_is_the_one_the_module_uses() -> None:
    """**单一来源**：注册表里的模板必须就是模块里那个常量，而不是抄来的副本。"""
    from app.rag.memory import PROMPT as MEMORY_TEMPLATE
    from app.rag.wiki import PROMPT as WIKI_TEMPLATE

    assert registry().get("wiki.claims").template == WIKI_TEMPLATE
    assert registry().get("memory.candidates").template == MEMORY_TEMPLATE


def test_key_includes_the_version() -> None:
    assert registry().get("wiki.claims").key == "wiki.claims@1"
    assert registry().get("wiki.claims", version=1).key == "wiki.claims@1"


def test_unknown_name_or_version_is_rejected() -> None:
    with pytest.raises(PromptError, match="没有注册名为"):
        registry().get("nope.nothing")
    with pytest.raises(PromptError, match="没有第 9 版"):
        registry().get("wiki.claims", version=9)


def test_render_fills_variables_and_rejects_mismatches() -> None:
    """缺变量与**多变量**都报错：多传往往意味着模板改过而调用方没跟上。"""
    spec = registry().get("wiki.claims")

    text = spec.render(title="标题", chunks="正文", max_per_chunk=3, example="{}", kinds="a/b")
    assert "标题" in text and "正文" in text

    with pytest.raises(PromptError, match="缺少变量"):
        spec.render(title="标题")
    with pytest.raises(PromptError, match="模板里没有的变量"):
        spec.render(title="t", chunks="c", max_per_chunk=1, example="{}", kinds="a", unexpected="x")


def test_static_prompts_are_not_treated_as_templates() -> None:
    """`agent.system` 里有 JSON 示例：那些花括号是 JSON，不是字段名。

    这正是 E4 踩过的坑（示例直接写进模板 → `str.format` 当成字段名 →
    报一个与真实原因毫不相干的 KeyError）。注册表的自检当初就是被它顶出来的。
    """
    spec = registry().get("agent.system")

    assert spec.interpolated is False
    assert declared_variables(spec.template), "模板里确实有花括号，所以必须靠标记区分"
    assert spec.render() == spec.template, "原样返回"
    with pytest.raises(PromptError, match="原样发送"):
        spec.render(thought="x")


def test_declared_variables_handles_format_quirks() -> None:
    """`{a!r}`、`{a:>4}`、`{{}}` 这些形态正则容易数错 —— 用 Formatter 才准。"""
    assert declared_variables("{a} {b!r} {c:>4} {{这不是字段}}") == {"a", "b", "c"}


def test_duplicate_registration_is_rejected() -> None:
    registry_under_test = PromptRegistry()
    spec = PromptSpec(name="x", version=1, template="t", variables=(), description="d")
    registry_under_test.register(spec)

    with pytest.raises(PromptError, match="重复注册"):
        registry_under_test.register(spec)


def test_self_check_reports_both_kinds_of_mismatch() -> None:
    broken = PromptRegistry(
        [
            PromptSpec(
                name="broken",
                version=1,
                template="{used} 与 {undeclared}",
                variables=("used", "never_used"),
                description="d",
            )
        ]
    )

    problems = registry_problems_of(broken)

    assert any("未声明的变量：undeclared" in item for item in problems)
    assert any("没用到的变量：never_used" in item for item in problems)


def registry_problems_of(custom: PromptRegistry) -> list[str]:
    from app.prompts.registry import self_check

    return self_check(custom)


def test_evaluation_is_honest_about_being_missing() -> None:
    """没评测过的提示词必须留空 —— 编一个「效果良好」比留白危险得多。"""
    rows = {row["key"]: row for row in registry().describe()}

    assert rows["qa.system@1"]["evaluation"] is not None, "这条确实在黄金集上量过"
    assert rows["agent.system@1"]["evaluation"] is None
    assert rows["memory.candidates@1"]["evaluation"] is None


def test_describe_carries_metadata_but_not_full_templates() -> None:
    """只出元数据：模板可能很长，而出口的用途是「哪一版、测过没有」。"""
    row = next(item for item in registry().describe() if item["key"] == "wiki.claims@1")

    assert row["variables"] == ["title", "chunks", "max_per_chunk", "example", "kinds"]
    assert row["roles"] == ["chat"]
    assert row["outputSchema"]
    assert "template" not in row


def test_build_registry_is_fresh_each_call() -> None:
    """装配是纯函数（缓存只放在 `registry()` 上）：测试之间不该互相污染。"""
    assert build_registry() is not build_registry()


async def test_prompts_endpoint_returns_metadata_and_clean_self_check() -> None:
    secret = str(load_vector()["secret"])
    monkeypatch_time()
    from app.core.internal_auth import InternalRequestVerifier

    app: FastAPI = create_app(verifier=InternalRequestVerifier(secret))
    body = json.dumps({})
    headers = {
        **signed_headers("GET", "/prompts", secret=secret, body=body, role="ADMIN", user_id=1),
        "Content-Type": "application/json",
    }

    response = await call(app, "GET", "/prompts", headers=headers, content=body)
    payload = response.json()

    assert response.status_code == 200
    assert payload["problems"] == [], "真实目录必须自检通过"
    keys = {item["key"] for item in payload["prompts"]}
    assert "qa.system@1" in keys
    assert all("template" not in item for item in payload["prompts"])


async def test_prompts_endpoint_needs_internal_signature() -> None:
    app: FastAPI = create_app()

    response = await call(app, "GET", "/prompts")

    assert response.status_code == 401


def monkeypatch_time() -> None:
    """把签名校验用的时间固定住（与其它内部端点用例同样的做法）。"""
    import app.core.internal_auth as internal_auth

    internal_auth.time.time = lambda: FIXED_TIMESTAMP_MS / 1000  # type: ignore[assignment]
