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

from app.core.config import ENV_FILE, env_source
from app.core.crypto import decrypt, load_master_key
from app.providers.models import ProviderCapabilities, ProviderConfig
from app.providers.url_policy import check_base_url

# ⚠️ 为什么必须 import `app.core.config`（而不是顺手删掉这行）：
# `.env` 的加载挂在那**一个模块的 import 期**（见 `app/core/config.py` 的 `load_dotenv`），
# 而本模块读 MYSQL_* / 主密钥走的是 `os.environ`。少了这行就会
# 「.env 里 mysql 配得齐齐的，这里却判定未配置」→ 返回 0 条配置 →
# 上层报「角色尚未配置」，把「配置没读到」伪装成「没配过」。
# 依赖放在**使用点旁边**（而不是只放在 app.main）才不会被别的入口漏掉：
# CLI 脚本与 fixture 脚本都不经过 app.main。
# `ENV_FILE` / `env_source` 只用于日志与诊断，不参与判断。

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
    rows: Iterable[Mapping[str, object]], *, decrypt_key: bool = True, allow_private: bool = True
) -> list[ProviderConfig]:
    """把 `ai_provider_config` 的行变成 `ProviderConfig`。

    **解不开密钥的行直接报错，不跳过**：跳过会让「某个角色悄悄没配上」，
    而现象是「这个功能用不了但其它都正常」—— 比整体装配失败更难查。

    参数刻意叫 `decrypt_key` 而不是 `decrypt`：后者会**遮蔽**同名的解密函数，
    让函数体内再也调不到它（mypy 报的是 "Literal[True] not callable"，很好笑但很难查）。

    :param allow_private: 是否允许内网/loopback 地址（**个人配置传 False**，见 `url_policy`）
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
        base_url = _base_url_of(row, allow_private=allow_private)
        capabilities = ProviderCapabilities(
            chat=capability == "chat",
            embedding=capability == "embedding",
            rerank=capability == "rerank",
        )
        configs.append(
            ProviderConfig(
                role=role,
                provider=str(row.get("provider") or "openai_compatible"),
                base_url=base_url,
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


def _base_url_of(row: Mapping[str, object], *, allow_private: bool) -> str:
    """取并校验 `base_url`。

    `fake` 协议不需要真实地址（它不发出站请求），所以空地址放行；
    其余协议**当场校验**：把「地址填错」暴露在装配期，而不是等用户问一句话才报网络错。
    """
    raw = str(row.get("base_url") or "").strip()
    if not raw and str(row.get("provider") or "") == "fake":
        return raw
    check_base_url(raw, allow_private=allow_private)
    return raw


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
#:
#: `user_id = 0` 是**全局配置**（站长在 AI 实验室里配的那份）。
#: ⚠️ 全局查询**必须带这个条件**：漏了会把某个用户的私人模型当成全站默认，
#: 那是最严重的一种串号（别人问问题用的是他的 Key）。
SELECT_GLOBAL_CONFIGS = (
    "SELECT `role`, `provider`, `base_url`, `model`, `api_key_cipher`, `dimension`, "
    "`timeout_ms`, `max_tokens`, `temperature` "
    "FROM `ai_provider_config` WHERE `enabled` = 1 AND `user_id` = 0 ORDER BY `role`"
)

#: 某个用户的生效配置：他自己的行 + 全局行（按角色由调用方做覆盖）
SELECT_CONFIGS_WITH_USER = (
    "SELECT `user_id`, `role`, `provider`, `base_url`, `model`, `api_key_cipher`, `dimension`, "
    "`timeout_ms`, `max_tokens`, `temperature` "
    "FROM `ai_provider_config` WHERE `enabled` = 1 AND `user_id` IN (0, %s) "
    "ORDER BY `user_id`"
)

#: 迁移还没执行时的兜底查询（没有 `user_id` 列）。**只在报「Unknown column」时用**。
#: 注意它**不带 `user_id` 条件**（那列还不存在），所以它读到的就是迁移前的全局行。
SELECT_LEGACY_CONFIGS = (
    "SELECT `role`, `provider`, `base_url`, `model`, `api_key_cipher`, `dimension`, "
    "`timeout_ms`, `max_tokens`, `temperature` "
    "FROM `ai_provider_config` WHERE `enabled` = 1 ORDER BY `role`"
)

#: 个人配置只放开这几个角色 —— 见 `load_provider_configs` 的说明（索引只有一份）
USER_SCOPED_ROLES = frozenset({"chat", "fast", "reasoning"})


def load_provider_configs(user_id: int | None = None) -> list[ProviderConfig]:
    """装配用的配置来源：**面板是唯一权威**。

    读取顺序（先到先用，不做合并 —— 合并会让「到底哪份生效」变成一个需要推理的问题）：
    1. `AI_PROVIDER_CONFIG_JSON`：容器/CI 显式注入的完整明文配置；
    2. `MYSQL_*` 齐备时直连库读 `ai_provider_config` 并解密（与 Java 同一张表、同一把主密钥）；
    3. 都没有：返回空列表 → 装配时抛「角色尚未配置」，**不退回 Fake**。

    第 3 条是关键：没有「没配也能跑」的默认。宁可让 `/qa` 明确报「请去面板配置」，
    也不要让假模型把「没配好」伪装成「回答质量差」。

    **个人配置（`user_id` 非空）**：该用户自己的行按角色**覆盖**全局行，没配的角色回落到全局。
    但有两条硬约束：

    * **只放开 `USER_SCOPED_ROLES`（chat/fast/reasoning）**：`embedding`/`rerank` 不按用户隔离。
      原因是**向量索引只有一份** —— 索引是用某个嵌入模型建的，换一个模型去检索，
      向量不在同一空间，结果不是「差一点」而是**错的**。要让每个用户用不同嵌入模型，
      前提是按模型各建一份索引（成本随模型数线性增长），那是另一个决定。
      用户行里出现这两个角色会被**忽略并警告**（不是静默生效，也不是报错挡住整个装配）。
    * **个人地址必须过 `url_policy`（禁内网）**：`base_url` 是服务端拿去发请求的地址，
      让普通用户填就等于开放 SSRF。
    """
    from_env = configs_from_env()
    if from_env:
        # 环境变量注入的是**整份**配置（容器/CI）。个人配置在这种部署下没有意义：
        # 它本来就是用来「没有库也能跑」的，返回同一份，别假装支持用户级。
        return from_env
    if not _mysql_configured():
        return []
    if not user_id:
        return configs_from_rows(_fetch_rows(), decrypt_key=True)

    rows = _fetch_rows(user_id=user_id)
    global_rows = [row for row in rows if _user_id_of(row) == 0]
    user_rows = [row for row in rows if _user_id_of(row) == user_id]

    merged: dict[str, Mapping[str, object]] = {
        str(row.get("role") or ""): row for row in global_rows
    }
    for row in user_rows:
        role = str(row.get("role") or "")
        if role not in USER_SCOPED_ROLES:
            logger.warning(
                "忽略用户 %s 的个人配置：角色 %s 不按用户隔离"
                "（向量索引只有一份，见 config_source 的说明）",
                user_id,
                role or "(空)",
            )
            continue
        merged[role] = row

    global_configs = configs_from_rows(global_rows, decrypt_key=True)
    user_configs = configs_from_rows(user_rows, decrypt_key=True, allow_private=False)
    user_roles = {config.role for config in user_configs} & USER_SCOPED_ROLES
    # 全局那份允许内网（站长自建推理就在 127.0.0.1）；用户那份已按公网校验过
    return [config for config in global_configs if config.role not in user_roles] + [
        config for config in user_configs if config.role in user_roles
    ]


def _user_id_of(row: Mapping[str, object]) -> int:
    """行归属：`0` = 全局（迁移前的所有行都是这个意思）。"""
    return _as_int(row.get("user_id")) or 0


def describe_sources() -> str:
    """说清「配置是从哪读的、为什么是空的」——**不含任何值**。

    为什么需要它：空配置有两种完全不同的原因，而它们此前报同一句话：
    - 面板还没配（真·未配置，去面板填就行）；
    - `MYSQL_*` 没给齐（面板配了也读不到 —— 这时让用户去面板重填是白费功夫）。
    把这两件事混成一句「角色尚未配置」，会把排查方向直接带偏。
    """
    parts: list[str] = []
    if configs_from_env():
        parts.append(f"来源：{CONFIG_JSON_ENV} 环境变量")
    elif _mysql_configured():
        host = os.environ.get("MYSQL_HOST")
        port = os.environ.get("MYSQL_PORT") or 3306
        parts.append(f"来源：MySQL {host}:{port} 的 ai_provider_config")
        if not _env_file_loaded():
            # .env 没读到但环境变量齐了：合法（容器注入），但要能看出来
            parts.append("（MYSQL_* 来自进程环境，不是 .env）")
    else:
        parts.append(
            f"没有可用的配置来源：{CONFIG_JSON_ENV} 未设置，"
            "且 MYSQL_HOST/MYSQL_DB/MYSQL_USER 未给齐"
        )
        parts.append(f"当前 .env：{env_source()}")
    return "；".join(parts)


def _env_file_loaded() -> bool:
    """`.env` 是否存在（只用于诊断输出，不参与判断）。"""
    return ENV_FILE.is_file()


def _mysql_configured() -> bool:
    return all(os.environ.get(key) for key in ("MYSQL_HOST", "MYSQL_DB", "MYSQL_USER"))


def _fetch_rows(user_id: int | None = None) -> list[Mapping[str, object]]:
    """只读查询 `ai_provider_config`。

    刻意**不在模块顶层 import pymysql**：它是可选依赖（只有直连库的部署才需要），
    顶层 import 会让「用环境变量注入配置」的场景平白多一个依赖。

    ⚠️ **迁移还没执行时的兜底**：`18_ai_user_provider_config.sql` 之前，表里没有 `user_id` 列，
    带它的查询会报 `1054 Unknown column`。这时**退回旧查询**并记一条 warn ——
    让整个 AI 服务因为「还没跑迁移」而起不来（或者所有用户问答一起 500）是更糟的结果。
    表现会是「个人配置保存后不生效」，而日志里那句话直接说明原因。
    """
    try:
        import pymysql  # type: ignore[import-untyped]  # noqa: PLC0415 - 可选依赖，按需导入
    except ImportError as error:  # pragma: no cover - 取决于部署是否装了它
        raise ProviderConfigError(
            "直连库读取配置需要 PyMySQL：请安装它，或改用 AI_PROVIDER_CONFIG_JSON 注入配置"
        ) from error

    try:
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
    except Exception as error:  # noqa: BLE001 - 可选依赖的异常类型不该逐个列举（列不全就漏）
        raise _db_error("连接 MySQL", error) from error
    try:
        with connection.cursor() as cursor:
            try:
                if user_id:
                    cursor.execute(SELECT_CONFIGS_WITH_USER, (user_id,))
                else:
                    cursor.execute(SELECT_GLOBAL_CONFIGS)
            except Exception as error:  # noqa: BLE001 - 只为识别「列还不存在」，其余照旧抛
                if not _looks_like_missing_user_id(error):
                    raise
                logger.warning(
                    "ai_provider_config 还没有 user_id 列（迁移 18_ai_user_provider_config.sql "
                    "尚未执行）：本次只读全局配置，个人模型配置不会生效"
                )
                cursor.execute(SELECT_LEGACY_CONFIGS)
            return list(cursor.fetchall())
    except Exception as error:  # noqa: BLE001 - 同上：查询期的异常统一翻成配置错误
        raise _db_error("查询 ai_provider_config", error) from error
    finally:
        connection.close()


def _looks_like_missing_user_id(error: Exception) -> bool:
    """是不是「user_id 列不存在」这一类错误。

    只看**错误码/关键字**，不靠异常类型（PyMySQL 在不同版本里给出的类型不一致）。
    """
    text = str(error)
    return (
        "1054" in text or "Unknown column 'user_id'" in text or "Unknown column `user_id`" in text
    )


def _db_error(stage: str, error: Exception) -> ProviderConfigError:
    """把 PyMySQL 的异常翻成**可读的配置错误**（不回显密码）。

    为什么必须翻（实测踩到）：`_fetch_rows` 原先不接异常，一个 1044
    （`Access denied for user 'root'@'%' to database 'stellar_ink'` —— 账号只有 USAGE 权限）
    会原样冒到端点，而端点的 `ASSEMBLY_ERRORS` 只认 `ProviderConfigError` /
    `ProviderError` / `CorpusError`，于是用户看到的是 `code=500「系统繁忙，请稍后重试」`：
    真因写在服务端日志的一句 MySQL 报错里，而界面把「配置读不到」伪装成了「服务坏了」。
    """
    host = os.environ.get("MYSQL_HOST")
    port = os.environ.get("MYSQL_PORT") or 3306
    target = f"{host}:{port}/{os.environ.get('MYSQL_DB')}"
    text = str(error).strip()
    hint = ""
    if "1044" in text or "1045" in text:
        hint = "（账号对目标库没有权限或密码不对：核对 MYSQL_USER / MYSQL_PASSWORD 与服务端授权）"
    elif "2003" in text or "Can't connect" in text:
        hint = "（连不上：核对 MYSQL_HOST、以及 SSH 隧道是否还开着）"
    return ProviderConfigError(
        f"{stage}时读不到模型配置：{target} —— {type(error).__name__}: {text}{hint}"
    )


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
