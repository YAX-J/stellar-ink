"""服务配置：只读环境变量，非敏感可调项后续走 Nacos（见 roadmap §18）。

本模块**刻意不定义任何密钥字段**（``AI_API_KEY`` / ``AI_INTERNAL_SECRET`` 等）：
密钥由各自的读取路径（``app/core/internal_auth.py`` 与 ``app/core/crypto.py``）
直接从 ``os.environ`` 取，**缺失即拒绝相关能力、不允许有默认值**（红线 §7.1）。

⚠️ 一个必须显式做的事：**`.env` 要手动 load 一遍**。
`pydantic-settings` 只会把 `.env` 里的键喂给 `Settings` 的字段，
**不会**把它们放进 `os.environ`（实测：`AI_PORT` 生效了，`AI_INTERNAL_SECRET` 却读不到）。
于是「把密钥写进 `.env` 就能用」这件事不会自动成立 —— 看起来像密钥填错了，
排查方向会跑到 Java 侧去。这里在 import 期用 `load_dotenv` 补上，
且默认 `override=False`：**真实环境变量优先于 `.env`**（生产注入的密钥不会被文件盖掉）。
"""

import os  # noqa: F401 - 说明见模块 docstring：密钥由 os.environ 直读，本文件负责把它灌满
from functools import lru_cache
from pathlib import Path
from typing import Literal

from dotenv import load_dotenv
from pydantic_settings import BaseSettings, SettingsConfigDict

AppEnv = Literal["dev", "prod", "test"]

#: 本服务的根目录（app/core/config.py → 上两级）。显式定位而不是靠 cwd：
#: 从仓库根或任意目录启动 uvicorn 都应读到同一份 `.env`
PROJECT_ROOT = Path(__file__).resolve().parents[2]
ENV_FILE = PROJECT_ROOT / ".env"

#: import 期加载一次即可：`load_dotenv` 幂等，且默认不覆盖已有的真实环境变量
load_dotenv(ENV_FILE)


class Settings(BaseSettings):
    """进程级配置。环境变量前缀统一为 ``AI_``。"""

    model_config = SettingsConfigDict(
        env_prefix="AI_",
        # 与上面的 load_dotenv 指向同一个文件：Settings 字段与 os.environ 直读的密钥
        # 必须来自同一份配置，否则会出现「面板说配好了、验签说没配」这种分裂
        env_file=ENV_FILE,
        env_file_encoding="utf-8",
        extra="ignore",
        frozen=True,
    )

    #: 运行环境：dev / prod / test，影响日志格式与自检严格程度
    app_env: AppEnv = "dev"

    #: 服务名，用于日志与健康检查输出
    service_name: str = "stellar-ink-ai"

    #: 监听端口（与 docs/ai/development-workflow.md §4 的 8200 对齐；仅本机/内网暴露）
    port: int = 8200

    #: 日志级别
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"

    @property
    def is_prod(self) -> bool:
        return self.app_env == "prod"

    @property
    def env_file_loaded(self) -> bool:
        """`.env` 是否存在（探活/排障用：能一眼看出「配置到底读的哪一份」）。"""
        return ENV_FILE.is_file()


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """进程内单例。测试可通过 ``get_settings.cache_clear()`` 重置。"""
    return Settings()


def env_source() -> str:
    """给日志用的配置来源说明，**不含任何值**。"""
    return (
        f"{ENV_FILE}（存在）"
        if ENV_FILE.is_file()
        else f"{ENV_FILE}（不存在，全部用默认值与环境变量）"
    )
