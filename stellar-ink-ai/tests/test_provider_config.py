"""Provider 装配来源的测试：**面板是唯一权威，没有代码里的默认模型**。

守的核心是一条不变式：**没配就是没配** —— 装配时明确报「请去面板配置」，
而不是退回 Fake。退回的后果不是「功能不可用」，而是「功能看起来可用、质量很差」，
那是最难查的一类问题。

因此这里验三件事：
1. 空配置 → 取模型必抛错（且错误里要说清怎么办）；
2. 配置变了 → 指纹变、实例重建；配置没变 → 复用同一份（不每次新建连接池）；
3. 显式声明 `provider="fake"` 仍然可用（离线自测与测试靠它，但它必须是**显式的**）。
"""

from __future__ import annotations

import pytest

from app.api.v1.assembly import ASSEMBLY_ERRORS, CorpusError
from app.providers.config_source import (
    ProviderConfigError,
    _fetch_rows,
    configs_from_env,
    configs_from_rows,
)
from app.providers.errors import ProviderError, UnsupportedCapabilityError
from app.providers.models import ProviderCapabilities, ProviderConfig
from app.providers.resolver import ProviderResolver, fingerprint_of


def fake(role: str, capability: str, model: str = "fake") -> ProviderConfig:
    return ProviderConfig(
        role=role,
        provider="fake",
        base_url="http://fake.local",
        model=model,
        capabilities=ProviderCapabilities(**{capability: True}),
    )


# --------------------------------------------------------------- 没有默认值


def test_empty_config_refuses_to_build_a_model() -> None:
    """什么都没配时**必须报错**，不能返回一个能用但假的模型。"""
    resolver = ProviderResolver(source=lambda: ())

    with pytest.raises(ProviderError) as error:
        resolver.registry().chat_model()

    assert "尚未配置" in str(error.value)
    assert "AI 实验室" in str(error.value), "错误要说清去哪儿配，否则等于只说了「不行」"


def test_missing_role_is_reported_by_its_own_name() -> None:
    """只配了 chat，问 embedding 角色时要报 embedding 没配（不是含糊的「没配」）。"""
    resolver = ProviderResolver(source=lambda: (fake("chat", "chat"),))

    assert resolver.registry().has("chat") is True
    with pytest.raises(UnsupportedCapabilityError, match="embedding"):
        resolver.registry().embedding_model()


def test_fake_is_allowed_but_only_when_declared() -> None:
    """`fake` 仍然可用（离线自测/测试），但它必须来自**配置**，不是代码默认。"""
    resolver = ProviderResolver(source=lambda: (fake("chat", "chat"),))

    model = resolver.registry().chat_model()

    assert getattr(model, "MODEL_TAG", "") == "fake"


def test_capability_mismatch_is_caught_at_lookup() -> None:
    """给 embedding 角色配一个纯 chat 的实例：取用时就要报错，而不是调用到一半才炸。"""
    wrong = ProviderConfig(
        role="embedding",
        provider="fake",
        base_url="http://fake.local",
        model="fake",
        capabilities=ProviderCapabilities(chat=True),
    )
    resolver = ProviderResolver(source=lambda: (wrong,))

    with pytest.raises(UnsupportedCapabilityError, match="embedding"):
        resolver.registry().embedding_model()


# --------------------------------------------------------------- 指纹缓存


def test_same_config_reuses_the_registry() -> None:
    resolver = ProviderResolver(source=lambda: (fake("chat", "chat"),))

    first = resolver.registry()
    second = resolver.registry()

    assert first is second, "配置没变却重建了实例：每次请求都会新建一个连接池"


def test_changed_config_rebuilds_the_registry() -> None:
    """换了模型（或换了端点）必须换实例 —— 否则面板改了配置也不生效。"""
    state = {"model": "first"}
    resolver = ProviderResolver(source=lambda: (fake("chat", "chat", state["model"]),))

    first = resolver.registry()
    state["model"] = "second"
    second = resolver.registry()

    assert first is not second
    assert second.config_of("chat") is not None
    assert second.config_of("chat").model == "second"  # type: ignore[union-attr]


def test_fingerprint_ignores_row_order() -> None:
    """配置来自数据库查询，**行序不保证稳定**：不排序会让缓存永远失效。"""
    configs = (fake("chat", "chat"), fake("embedding", "embedding"))

    assert fingerprint_of(configs) == fingerprint_of(tuple(reversed(configs)))


def test_fingerprint_changes_with_any_role() -> None:
    base = [fake("chat", "chat")]
    added = [fake("chat", "chat"), fake("embedding", "embedding")]

    assert fingerprint_of(base) != fingerprint_of(added)


def test_fingerprint_never_contains_the_key() -> None:
    """指纹要进日志：一旦它含密钥就等于把密钥写进日志。"""
    with_key = ProviderConfig(
        role="chat",
        provider="openai_compatible",
        base_url="https://example.invalid/v1",
        model="m",
        api_key="sk-secret-value-must-not-appear",
        capabilities=ProviderCapabilities(chat=True),
    )
    without_key = ProviderConfig(
        role="chat",
        provider="openai_compatible",
        base_url="https://example.invalid/v1",
        model="m",
        api_key="another-key",
        capabilities=ProviderCapabilities(chat=True),
    )

    digest = fingerprint_of([with_key])

    assert "sk-" not in digest and "secret" not in digest
    # 密钥轮换不影响指纹：指纹标识的是「用哪个模型」，不是「用哪把钥匙」
    assert digest == fingerprint_of([without_key])


def test_invalidate_forces_a_rebuild() -> None:
    resolver = ProviderResolver(source=lambda: (fake("chat", "chat"),))
    first = resolver.registry()

    resolver.invalidate()

    assert resolver.registry() is not first


# --------------------------------------------------------------- 来源解析


def test_rows_are_translated_with_capabilities_and_defaults() -> None:
    rows = [
        {
            "role": "chat",
            "provider": "fake",
            "base_url": "http://fake.local",
            "model": "fake",
            "timeout_ms": None,
            "dimension": "1024",
        }
    ]

    configs = configs_from_rows(rows)

    assert len(configs) == 1
    assert configs[0].capabilities.chat is True
    assert configs[0].capabilities.embedding is False
    assert configs[0].timeout_ms == 30_000, "超时缺省要有确定值（否则 httpx 会无限等）"
    assert configs[0].dimension == 1024, "字符串维度也要能转（JSON 注入时常见）"


def test_unknown_role_is_rejected_not_guessed() -> None:
    """库里出现了代码不认识的角色：报错，不要「猜一个能力」装出错的实例。"""
    with pytest.raises(ProviderConfigError, match="未知的模型角色"):
        configs_from_rows(
            [{"role": "translate", "provider": "fake", "model": "x", "base_url": "u"}]
        )


def test_non_fake_row_without_key_is_rejected() -> None:
    """真实协议缺密钥：报错（提示去面板重填），不能装出一个「没有钥匙的客户端」。"""
    with pytest.raises(ProviderConfigError, match="缺少密钥"):
        configs_from_rows(
            [{"role": "chat", "provider": "openai_compatible", "model": "m", "base_url": "u"}]
        )


def test_env_source_needs_no_database() -> None:
    """容器/CI 可以整份注入配置（含明文密钥），此时不需要数据库、也不去解密。"""
    payload = (
        '[{"role":"chat","provider":"fake","base_url":"http://fake.local","model":"fake",'
        '"apiKey":"plain-key"}]'
    )

    configs = configs_from_env(payload)

    assert configs[0].api_key == "plain-key"


def test_env_source_rejects_malformed_json() -> None:
    with pytest.raises(ProviderConfigError, match="合法 JSON"):
        configs_from_env("{not json")

    with pytest.raises(ProviderConfigError, match="必须是数组"):
        configs_from_env('{"role":"chat"}')


def test_env_source_empty_means_no_configs() -> None:
    """没注入就返回空 —— 空配置在装配时会被明确拒绝，而不是悄悄降级。"""
    assert configs_from_env("") == []


def test_provider_error_is_the_common_base_for_callers() -> None:
    """端点按 `ASSEMBLY_ERRORS`（`ProviderError` / `ProviderConfigError` / `CorpusError`）捕获：
    三者都要能被同一个 except 收到，否则「没配好」会漏成 500。

    `CorpusError` 不在 `ProviderError` 树下是有意的：它是「环境里的语料不可用」，
    与「模型配错了」是两类问题，只有状态码相同（400），排查方向完全不同。
    """
    assert issubclass(UnsupportedCapabilityError, ProviderError)
    assert issubclass(ProviderConfigError, RuntimeError)
    assert not issubclass(CorpusError, ProviderError)
    assert all(
        issubclass(error_type, ASSEMBLY_ERRORS)
        for error_type in (ProviderError, ProviderConfigError, CorpusError)
    )


def test_database_failure_is_translated_not_leaked_as_a_500(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """读库失败必须变成 `ProviderConfigError`，否则用户看到的是「服务坏了」。

    实测踩到的那次：测试机的 `root@'%'` 只有 `USAGE` 权限，直连报
    `1044 Access denied ... to database 'stellar_ink'`。这条异常原先原样冒到端点，
    而 `ASSEMBLY_ERRORS` 不认它 → `code=500「系统繁忙，请稍后重试」`。
    真因是一句权限问题，界面却把它说成服务故障 —— 排查方向直接跑偏。

    同时守住「不泄露密码」：错误消息里不能出现 `MYSQL_PASSWORD` 的值。
    """
    pymysql = pytest.importorskip("pymysql", reason="直连库读取是可选能力")
    secret = "s3cr3t-not-in-messages"

    def explode(**_kwargs: object) -> object:
        raise pymysql.err.OperationalError(
            1044, "Access denied for user 'root'@'%' to database 'stellar_ink'"
        )

    monkeypatch.setattr(pymysql, "connect", explode)
    monkeypatch.setenv("MYSQL_HOST", "124.221.158.32")
    monkeypatch.setenv("MYSQL_DB", "stellar_ink")
    monkeypatch.setenv("MYSQL_USER", "root")
    monkeypatch.setenv("MYSQL_PASSWORD", secret)

    with pytest.raises(ProviderConfigError) as excinfo:
        _fetch_rows()

    message = str(excinfo.value)
    assert "1044" in message
    assert "124.221.158.32:3306/stellar_ink" in message, "要说清读的是哪个库"
    assert "MYSQL_PASSWORD" in message, "要给出该查什么"
    assert secret not in message
