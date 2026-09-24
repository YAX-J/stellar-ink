"""服务配置：只读环境变量，非敏感可调项后续走 Nacos（见 roadmap §18）。

本模块**刻意不定义任何密钥字段**（``AI_API_KEY`` / ``AI_INTERNAL_SECRET`` 等）：
M0 全程 Fake Adapter，不需要密钥；等到 M2/M6 引入真实 Provider 时，
密钥也必须走「缺失即拒绝启动」的独立读取路径，不允许有默认值（红线 §7.1）。
"""

from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict

AppEnv = Literal["dev", "prod", "test"]


class Settings(BaseSettings):
    """进程级配置。环境变量前缀统一为 ``AI_``。"""

    model_config = SettingsConfigDict(
        env_prefix="AI_",
        env_file=".env",
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


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """进程内单例。测试可通过 ``get_settings.cache_clear()`` 重置。"""
    return Settings()
