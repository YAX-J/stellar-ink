"""`POST /wiki/claims` 的 HTTP 出口：签名保护、契约形状、以及「没配模型」的可操作提示。

这一层要守的两件事：
1. **没配 chat 模型时必须给可操作的提示**（去哪儿配），而不是回一个「0 条主张」的空结果 ——
   后者看起来像「抽取质量差」，实际是根本没配模型（这是本仓库反复强调的一类错）。
2. 统计字段（`proposed` / `kept` / `dropped`）**必须真的在响应里**：
   调用方靠它判断「是模型不行还是校验挡掉了」。
"""

from __future__ import annotations

import pytest
from fastapi import FastAPI

from app.main import create_app
from tests.fake_providers import install_fake_providers
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers

PATH = "/wiki/claims"


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    from app.core.internal_auth import InternalRequestVerifier

    install_fake_providers()
    return create_app(verifier=InternalRequestVerifier(secret))


async def post(app: FastAPI, secret: str, payload: dict | None = None) -> tuple[int, dict]:
    import json

    body = json.dumps(payload or {})
    headers = {
        **signed_headers("POST", PATH, secret=secret, body=body, role="ADMIN", user_id=1),
        "Content-Type": "application/json",
    }
    response = await call(app, "POST", PATH, headers=headers, content=body)
    return response.status_code, response.json()


async def test_claims_need_internal_signature(app: FastAPI) -> None:
    response = await call(app, "POST", PATH, json={})

    assert response.status_code == 401


async def test_claims_return_contract_shape(app: FastAPI, secret: str) -> None:
    """离线 Fake 模型抽不出主张是**正常**的；这里验的是契约形状与统计字段在位。"""
    status, body = await post(app, secret, {"maxPosts": 1})

    assert status == 200
    assert body["claims"] == []
    stats = body["stats"]
    assert stats["posts"] == 1, "Fake 也真的跑了一篇，不是 0"
    assert stats["proposed"] == 0 and stats["kept"] == 0
    assert stats["dropped"] == {}
    assert body["latencyMs"] >= 0


async def test_bounds_are_validated(app: FastAPI, secret: str) -> None:
    """越界的成本闸门要被**契约层**挡下（`maxPosts` 是「每篇一次模型调用」的上限）。

    这里回 422（FastAPI 的校验错误）而不是 `{code, message}`：它是**契约违规**，
    在进处理器之前就被拒了。Java 侧同样有 `@Min/@Max`，用户看到的是那边给的可读提示，
    所以这条路径不会把 `{detail}` 漏给用户（见 AGENTS §5「Java ↔ Python 的错误契约」）。
    """
    status, _ = await post(app, secret, {"maxPosts": 0})

    assert status == 422


async def test_missing_chat_model_gives_actionable_hint(
    monkeypatch: pytest.MonkeyPatch, secret: str
) -> None:
    """没配模型的提示必须能照着做（与其它 AI 端点同一条口径）。"""
    from app.core.internal_auth import InternalRequestVerifier
    from app.providers.config_source import ProviderConfigError

    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)

    def explode(*args: object, **kwargs: object) -> None:
        raise ProviderConfigError("角色 chat 尚未配置模型（请在 AI 实验室 → 模型配置里填写）")

    monkeypatch.setattr("app.providers.runtime.require_roles", explode)
    app = create_app(verifier=InternalRequestVerifier(secret))

    status, body = await post(app, secret, {"maxPosts": 1})

    # 装配失败 → 400 + `{code, message}`（不是 500：这是配置问题，不该伪装成服务故障）
    assert status == 400
    assert "模型配置" in body["message"], "提示里要有「去哪儿配」，否则用户只能猜"
    assert "来源" in body["message"], "空配置的两个原因（真没配 / 读不到）要分开说"


# ---------------------------------------------------------------- 定向重建（E4-11）


async def test_targeted_rebuild_only_extracts_the_asked_posts(app: FastAPI, secret: str) -> None:
    """`postIds` 是「就要这几篇」，不是「按顺序取几篇」。

    这条口径要紧：报告说「3 篇要重建」，实际却重建了「头 5 篇里的 1 篇」——
    现象是「点了重建却没变化」，而原因藏在两个参数的语义混用里。
    """
    status, body = await post(app, secret, {"postIds": [999], "maxPosts": 5})

    assert status == 200
    assert body["stats"]["posts"] == 0, "999 不在语料里 → 一篇都没抽"
    assert any("定向重建" in note for note in body["notes"]), "定向重建要说明抽了几篇"
    assert any("找不到" in note for note in body["notes"]), "请求了不存在的文章要如实说出来"


async def test_targeted_rebuild_rejects_oversized_list(app: FastAPI, secret: str) -> None:
    """一次请求能点几篇重建也是成本闸门（与 `maxPosts` 同一口径）。"""
    status, _ = await post(app, secret, {"postIds": list(range(1, 80))})

    assert status == 422


# ------------------------------------------------------------------ 失效盘点（E4-11）

STALE_PATH = "/wiki/stale"


async def post_stale(app: FastAPI, secret: str, payload: dict) -> tuple[int, dict]:
    import json

    body = json.dumps(payload)
    headers = {
        **signed_headers("POST", STALE_PATH, secret=secret, body=body, role="ADMIN", user_id=1),
        "Content-Type": "application/json",
    }
    response = await call(app, "POST", STALE_PATH, headers=headers, content=body)
    return response.status_code, response.json()


async def test_stale_needs_internal_signature(app: FastAPI) -> None:
    response = await call(app, "POST", STALE_PATH, json={"claims": []})

    assert response.status_code == 401


async def test_stale_reports_three_states_separately(app: FastAPI, secret: str) -> None:
    """ "内容变了"与"段落没了"处置不同（重建 / 清理），必须在响应里分得开。"""
    status, body = await post_stale(
        app,
        secret,
        {"claims": [{"postId": 999, "chunkIndex": 0, "contentHash": "whatever"}]},
    )

    assert status == 200
    assert body["checked"] == 1
    assert body["orphan"] == 1, "语料里没有这篇 → 段落已不存在"
    assert body["stale"] == 0
    assert body["orphanPostIds"] == [999]
    assert body["notes"], "状态的含义要写出来，否则没人知道该干什么"


async def test_stale_of_empty_list_says_nothing_to_do(app: FastAPI, secret: str) -> None:
    status, body = await post_stale(app, secret, {"claims": []})

    assert status == 200
    assert (body["checked"], body["current"], body["stale"], body["orphan"]) == (0, 0, 0, 0)
    assert any("不需要重建" in note for note in body["notes"])
