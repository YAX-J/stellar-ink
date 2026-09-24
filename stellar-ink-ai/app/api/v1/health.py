"""内部健康检查（仅内网可达）。

与 Java 侧 ``/ai/health`` 的分工：
- 本文的 ``GET /health``：Python 进程自身的存活与版本，暴露给 ``ai-service`` 探活。
- ``ai-service`` 的 ``/ai/health``：对浏览器的统一出口，聚合 Python 探测结果，
  且按红线要求**不泄露任何配置细节**。
"""

from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel, Field

from app import __version__
from app.core.config import get_settings

router = APIRouter(tags=["internal"])


class HealthResponse(BaseModel):
    """探活响应：只有判定存活必需的最小信息。"""

    service: str = Field(description="服务名")
    version: str = Field(description="服务版本")
    status: Literal["ok"] = Field(default="ok", description="固定为 ok，进程存活即返回")
    env: str = Field(description="运行环境名，便于区分 dev/prod 探活")


@router.get("/health", summary="Python AI 服务健康检查")
async def health() -> HealthResponse:
    settings = get_settings()
    return HealthResponse(
        service=settings.service_name,
        version=__version__,
        env=settings.app_env,
    )
