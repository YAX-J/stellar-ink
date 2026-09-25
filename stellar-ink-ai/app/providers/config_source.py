"""装配来源：**只从面板配置**（`ai_provider_config`）取模型，没有代码里的默认值。

为什么读库而不是走 Java 的 `/ai/admin/providers/runtime`：
- 那份接口带 ADMIN 门槛（它本来是给面板用的），让 Python 冒充 ADMIN 去读自己的配置
  会多出一条需要维护的「机器身份」，而它读的是同一张表；
- 密钥的密文本来就要在本服务解密（E 阶段的画像/Agent 都要用主密钥），
  走库只多一层连接、不增加信任边界。
Java 侧的 `/runtime` 保留给面板与排障使用，两条路读同一张表，不是两套配置。

**只读**：本模块只会 `SELECT`。写配置的唯一入口是面板（经 ai-service），
Python 不写 `ai_*` 表 —— 与「AI 能力归 Python、配置 CRUD 归 Java」的分工一致。
"""

from __future__ import annotations

import json
import logging
import os
from collections.abc import Iterable, Mapping

from app.core.crypto import decrypt, load_master_key
from app.providers.models import ProviderCapabilities, ProviderConfig

logger = logging.getLogger(__name__)

#: 角色 → 能力：与 `app/providers/registry.py` 的 `_ROLE_CAPABILITY` 同源，
#: 但这里要**在建实例之前**就知道能力，因为 `ProviderConfig.capabilities` 必须显式声明
_ROLE_CAPABILITY: dict[str, str] = {
    "chat": "chat",
    "fast": "chat",
    "reasoning": "chat",
    "embedding": "embedding",
    "rerank": "rerank",
}

#: 库连不上/密钥解不开时的环境变量：允许直接注入一份 JSON（容器与 CI 用）。
#: 键与列名同口径，值里 `apiKey` 是**明文**（它来自环境变量，不是库里的密文）
CONFIG_JSON_ENV = "AI_PROVIDER_CONFIG_JSON"


class ProviderConfigError(RuntimeError):
    """配置来源不可用（库连不上、密文解不开、字段缺失）。"""


def configs_from_rows(
    rows: Iterable[Mapping[str, object]], *, decrypt_key: bool = True
) -> list[ProviderConfig]:
    """把 `ai_provider_config` 的行变成 `ProviderConfig`。

    **解不开密钥的行直接报错，不跳过**：跳过会让「某个角色悄悄没配上」，
    而现象是「这个功能用不了但其它都正常」—— 比整体装配失败更难查。

    参数刻意叫 `decrypt_key` 而不是 `decrypt`：后者会**遮蔽**同名的解密函数，
    让函数体内再也调不到它（mypy 报的是 "Literal[True] not callable"，很好笑但很难查）。
    """
    configs: list[ProviderConfig] = []
    for row in rows:
        # 列名同时兼容 snake_case（库）与 camelCase（注入的 JSON，按契约口径）
        role = str(row.get("role") or "").strip()
        if not role:
            raise ProviderConfigError("配置行缺少 role")
        capability = _ROLE_CAPABILITY.get(role)
        if capability is None:
            # 库里出现了代码不认识的角色：宁可报错也不要「猜一个能力」装出错的实例
            raise ProviderConfigError(
                f"未知的模型角色：{role}（可选：{'/'.join(sorted(_ROLE_CAPABILITY))}）"
            )

        api_key = _api_key_of(row, decrypt_key=decrypt_key)
        capabilities = ProviderCapabilities(
            chat=capability == "chat",
            embedding=capability == "embedding",
            rerank=capability == "rerank",
        )
        configs.append(
            ProviderConfig(
                role=role,
                provider=str(row.get("provider") or "openai_compatible"),
                base_url=str(row.get("base_url") or ""),
                model=str(row.get("model") or ""),
                api_key=api_key,
                dimension=_as_int(row.get("dimension")),
                timeout_ms=_as_int(row.get("timeout_ms")) or 30_000,
                max_tokens=_as_int(row.get("max_tokens")),
                temperature=_as_float(row.get("temperature")),
                capabilities=capabilities,
            )
        )
    return configs


def _api_key_of(row: Mapping[str, object], *, decrypt_key: bool) -> str:
    """取明文密钥。

    两种来源：`api_key`（明文，来自环境变量注入）与 `api_key_cipher`（库里的密文）。
    密文走 `app/core/crypto.py` 的 `decrypt`，主密钥 `AI_SECRET_MASTER_KEY` 只在环境变量里。
    `fake` 协议不需要密钥（离线自测），允许为空 —— 这也是它唯一被允许的场景。
    """
    # 两种键名都认：库里的列是 snake_case，环境变量注入的 JSON 按契约用 camelCase。
    # 只认一种会让「注入的配置没有被读到」表现成「缺少密钥」，排查方向直接跑偏
    plain = str(row.get("api_key") or row.get("apiKey") or "")
    if plain:
        return plain
    cipher = row.get("api_key_cipher") or row.get("apiKeyCipher")
    if not cipher:
        if str(row.get("provider") or "") == "fake":
            return ""
        raise ProviderConfigError("配置缺少密钥：面板里重新填一次")
    if not decrypt_key:
        raise ProviderConfigError("当前来源不允许解密密钥")
    try:
        # 库里是 VARBINARY：先取回 `v1:<nonce>:<密文>` 这个文本形态，再用主密钥解
        token = (
            bytes(cipher).decode("utf-8") if isinstance(cipher, (bytes, bytearray)) else str(cipher)
        )
        return decrypt(token, key=load_master_key())
    except Exception as error:  # noqa: BLE001 - 统一转成可读的配置错误
        raise ProviderConfigError(f"密钥解密失败（主密钥是否与 Java 侧一致？）：{error}") from error


def configs_from_env(raw: str | None = None) -> list[ProviderConfig]:
    """从环境变量里读一份完整配置（容器与 CI 用；不需要数据库）。"""
    payload = raw if raw is not None else os.environ.get(CONFIG_JSON_ENV, "")
    if not payload.strip():
        return []
    try:
        parsed = json.loads(payload)
    except ValueError as error:
        raise ProviderConfigError(f"{CONFIG_JSON_ENV} 不是合法 JSON：{error}") from error
    if not isinstance(parsed, list):
        raise ProviderConfigError(f"{CONFIG_JSON_ENV} 必须是数组")
    return configs_from_rows([item for item in parsed if isinstance(item, dict)], decrypt_key=False)


#: 直接读库时需要的连接串（与 Java 侧同一套 MYSQL_* 变量；用 PyMySQL 只读访问）
MYSQL_ENV_KEYS = ("MYSQL_HOST", "MYSQL_PORT", "MYSQL_DB", "MYSQL_USER", "MYSQL_PASSWORD")

#: 查询语句：**只读**，且只碰 `ai_*` 表（红线 §7.2：Python 不读写 user/post）
SELECT_ENABLED_CONFIGS = (
    "SELECT `role`, `provider`, `base_url`, `model`, `api_key_cipher`, `dimension`, "
    "`timeout_ms`, `max_tokens`, `temperature` "
    "FROM `ai_provider_config` WHERE `enabled` = 1 ORDER BY `role`"
)


def load_provider_configs() -> list[ProviderConfig]:
    """装配用的配置来源：**面板是唯一权威**。

    读取顺序（先到先用，不做合并 —— 合并会让「到底哪份生效」变成一个需要推理的问题）：
    1. `AI_PROVIDER_CONFIG_JSON`：容器/CI 显式注入的完整明文配置；
    2. `MYSQL_*` 齐备时直连库读 `ai_provider_config` 并解密（与 Java 同一张表、同一把主密钥）；
    3. 都没有：返回空列表 → 装配时抛「角色尚未配置」，**不退回 Fake**。

    第 3 条是关键：没有「没配也能跑」的默认。宁可让 `/qa` 明确报「请去面板配置」，
    也不要让假模型把「没配好」伪装成「回答质量差」。
    """
    from_env = configs_from_env()
    if from_env:
        return from_env
    if _mysql_configured():
        return configs_from_rows(_fetch_rows(), decrypt_key=True)
    return []


def _mysql_configured() -> bool:
    return all(os.environ.get(key) for key in ("MYSQL_HOST", "MYSQL_DB", "MYSQL_USER"))


def _fetch_rows() -> list[Mapping[str, object]]:
    """只读查询 `ai_provider_config`。

    刻意**不在模块顶层 import pymysql**：它是可选依赖（只有直连库的部署才需要），
    顶层 import 会让「用环境变量注入配置」的场景平白多一个依赖。
    """
    try:
        import pymysql  # type: ignore[import-untyped]  # noqa: PLC0415 - 可选依赖，按需导入
    except ImportError as error:  # pragma: no cover - 取决于部署是否装了它
        raise ProviderConfigError(
            "直连库读取配置需要 PyMySQL：请安装它，或改用 AI_PROVIDER_CONFIG_JSON 注入配置"
        ) from error

    connection = pymysql.connect(
        host=os.environ["MYSQL_HOST"],
        port=int(os.environ.get("MYSQL_PORT") or 3306),
        user=os.environ["MYSQL_USER"],
        password=os.environ.get("MYSQL_PASSWORD") or "",
        database=os.environ["MYSQL_DB"],
        charset="utf8mb4",
        # 只读 + 显式超时：配置读不到应当快速失败，而不是把启动挂住
        cursorclass=pymysql.cursors.DictCursor,
        connect_timeout=3,
        read_timeout=5,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(SELECT_ENABLED_CONFIGS)
            return list(cursor.fetchall())
    finally:
        connection.close()


def _as_int(value: object) -> int | None:
    """宽松取整：库里是 INT，但 JSON 注入时可能是字符串（"30000"）。取不到就返回 None。"""
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float, str, bytes, bytearray)):
        try:
            return int(value)
        except (TypeError, ValueError):
            return None
    return None


def _as_float(value: object) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float, str, bytes, bytearray)):
        try:
            return float(value)
        except (TypeError, ValueError):
            return None
    return None
