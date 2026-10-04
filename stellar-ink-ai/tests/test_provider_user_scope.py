"""个人模型配置的隔离口径（M12 第一刀）。

让读者/作者也能配自己的模型，最危险的不是「配错」，而是**串号**与**越界**：

1. **串号**：用户 A 的模型/密钥被用户 B 用上（或反过来，A 的配置成了全站默认）。
   这份用例用「两个用户拿到不同实例」把它钉住；
2. **越界**：用户把 `embedding`/`rerank` 也配了 —— 那会让索引与查询不在同一个向量空间，
   结果是**错的**而不是「差一点」。所以用户级只放开 `chat`/`fast`/`reasoning`，
   别的角色**忽略并警告**；
3. **SSRF**：`base_url` 是服务端拿去发请求的地址，用户填内网地址必须被拒（见 `test_url_policy`）；
4. **有界**：解析器带连接池，按用户缓存必须有上限，否则是「每个来过的人都留一个池」。
"""

from __future__ import annotations

import pytest

from app.providers import runtime
from app.providers.config_source import USER_SCOPED_ROLES, configs_from_rows
from app.providers.errors import InvalidBaseUrlError, UnsupportedCapabilityError
from tests.fake_providers import fake_provider_configs, provider_config


@pytest.fixture(autouse=True)
def _restore_runtime() -> object:
    """每个用例后恢复全局装配：它是进程级状态，漏了就污染后面的用例。"""
    yield None
    runtime.use_provider_configs(fake_provider_configs())


def test_user_config_overrides_global_for_chat() -> None:
    global_configs = [provider_config("chat", model="global-chat")]
    runtime.use_provider_configs(global_configs)
    runtime.use_user_config_source(
        lambda user_id: [provider_config("chat", model=f"user{user_id}-chat")]
    )

    assert runtime.registry_for(1).config_of("chat").model == "user1-chat"  # type: ignore[union-attr]
    assert runtime.registry_for(2).config_of("chat").model == "user2-chat"  # type: ignore[union-attr]
    assert runtime.registry_for(None).config_of("chat").model == "global-chat"  # type: ignore[union-attr]


def test_users_do_not_share_registries() -> None:
    """两个用户的注册表必须是**不同实例**：共享就等于共享密钥与连接池。"""
    runtime.use_provider_configs([provider_config("chat", model="global-chat")])
    runtime.use_user_config_source(lambda user_id: [provider_config("chat", model=f"u{user_id}")])

    first = runtime.registry_for(11)
    second = runtime.registry_for(12)

    assert first is not second
    assert first is runtime.registry_for(11), "同一个用户要复用实例（否则每次都重握 TLS）"


def test_user_without_own_config_falls_back_to_global() -> None:
    """没配的用户回落到全局：这是默认状态，不是错误。

    真实实现里这条回落发生在 SQL 层（`user_id IN (0, uid)` 把全局行也带回来），
    这里用来源函数模拟同样的语义，断言的是「用户看到的就是全局那个模型」。
    """
    runtime.use_provider_configs([provider_config("chat", model="global-chat")])
    # 用户 7 只有全局行可用；用户 9 有自己的行
    runtime.use_user_config_source(
        lambda user_id: (
            [provider_config("chat", model=f"u{user_id}")]
            if user_id == 9
            else [provider_config("chat", model="global-chat")]
        )
    )

    assert runtime.registry_for(7).config_of("chat").model == "global-chat"  # type: ignore[union-attr]
    assert runtime.registry_for(9).config_of("chat").model == "u9"  # type: ignore[union-attr]


def test_require_roles_for_gives_an_actionable_message() -> None:
    """个人配置缺角色时，提示要指向「账号 → 我的 AI 模型」，而不是只说「没配」。"""
    runtime.use_provider_configs([provider_config("embedding", dimension=8)])
    runtime.use_user_config_source(lambda _user_id: [provider_config("embedding", dimension=8)])

    with pytest.raises(UnsupportedCapabilityError) as error:
        runtime.require_roles_for(5, "chat")

    assert "我的 AI 模型" in str(error.value)
    assert "回落" in str(error.value.detail), (
        "要说清「没配的角色会自动用全局」，否则用户不知道可以只配一个"
    )


def test_global_require_roles_is_unchanged() -> None:
    """全局预检照旧（不带用户）：别为了个人配置把原来的错误契约改了。"""
    runtime.use_provider_configs([])
    runtime.use_user_config_source(lambda _user_id: [])

    with pytest.raises(UnsupportedCapabilityError, match="尚未配置模型"):
        runtime.require_roles("chat")


def test_user_resolver_cache_is_bounded() -> None:
    """按用户缓存必须有上限：用户是无限的，不设限就是缓慢的内存泄漏。"""
    runtime.use_provider_configs([provider_config("chat", model="global-chat")])
    runtime.use_user_config_source(lambda user_id: [provider_config("chat", model=f"u{user_id}")])

    for user_id in range(1, runtime.MAX_USER_RESOLVERS + 6):
        runtime.registry_for(user_id)

    from app.providers.runtime import _user_resolvers  # noqa: PLC0415 - 只在断言里看内部状态

    assert len(_user_resolvers) == runtime.MAX_USER_RESOLVERS
    # 最早的那个被淘汰；最近用过的还在
    assert 1 not in _user_resolvers
    assert runtime.MAX_USER_RESOLVERS + 5 in _user_resolvers


def test_user_scoped_roles_are_only_generation_roles() -> None:
    """个人配置只放开生成类角色 —— 这是**设计约束**，不是实现细节。"""
    assert USER_SCOPED_ROLES == {"chat", "fast", "reasoning"}
    assert "embedding" not in USER_SCOPED_ROLES and "rerank" not in USER_SCOPED_ROLES


def test_loading_user_rows_ignores_embedding_and_warns(caplog: pytest.LogCaptureFixture) -> None:
    """用户行里出现 embedding/rerank：忽略并警告，**不**静默生效。

    静默生效的后果不是「慢」而是「错」：索引是用全局嵌入模型建的，
    拿另一个模型的向量去检索，相似度分数没有可比性。
    """
    from app.providers import config_source

    rows = [
        {"user_id": 0, "role": "chat", "provider": "fake", "base_url": "", "model": "g-chat"},
        {
            "user_id": 0,
            "role": "embedding",
            "provider": "fake",
            "base_url": "",
            "model": "g-embed",
        },
        {
            "user_id": 9,
            "role": "embedding",
            "provider": "fake",
            "base_url": "",
            "model": "u-embed",
        },
        {"user_id": 9, "role": "chat", "provider": "fake", "base_url": "", "model": "u-chat"},
    ]
    monkey = pytest.MonkeyPatch()
    try:
        monkey.setattr(config_source, "_mysql_configured", lambda: True)
        monkey.setattr(config_source, "_fetch_rows", lambda user_id=None: rows)
        monkey.setattr(config_source, "configs_from_env", lambda raw=None: [])

        with caplog.at_level("WARNING"):
            configs = config_source.load_provider_configs(user_id=9)
    finally:
        monkey.undo()

    by_role = {config.role: config.model for config in configs}
    assert by_role["chat"] == "u-chat", "用户自己的 chat 必须覆盖全局"
    assert by_role["embedding"] == "g-embed", "用户配的 embedding 必须被忽略（索引只有一份）"
    assert any("不按用户隔离" in record.getMessage() for record in caplog.records)


def test_user_base_url_must_be_public() -> None:
    """用户级配置的地址一律按「只允许公网」校验（站长那份允许内网）。"""
    user_row = {
        "user_id": 5,
        "role": "chat",
        "provider": "openai_compatible",
        "base_url": "http://127.0.0.1:8000/v1",
        "model": "local",
        "api_key": "sk-x",
    }

    with pytest.raises(InvalidBaseUrlError, match="内网或本机地址"):
        configs_from_rows([user_row], decrypt_key=False, allow_private=False)

    # 同一行按「站长填的」看是合法的（自建推理就在本机）
    assert configs_from_rows([user_row], decrypt_key=False, allow_private=True)[0].model == "local"
