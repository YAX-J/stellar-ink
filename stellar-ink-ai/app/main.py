"""星笺 AI 编排服务入口。

启动（本地）::

    uv run uvicorn app.main:app --host 127.0.0.1 --port 8200

M0 边界：只有探活与 traceId，没有任何模型、Qdrant、Redis 依赖；
真实 Provider 在 M2 引入，向量库在 M3 引入，M0–M1 全程 Fake Adapter。
"""

import logging

from fastapi import FastAPI

from app import __version__
from app.api.v1 import api_router
from app.core.config import get_settings
from app.core.logging import configure_logging
from app.core.trace import TraceIdMiddleware

logger = logging.getLogger(__name__)


def create_app() -> FastAPI:
    """应用工厂：测试可重复创建，避免模块级单例被测试互相污染。"""
    settings = get_settings()
    configure_logging(settings.log_level)

    application = FastAPI(
        title="星笺 AI 编排服务",
        version=__version__,
        description=(
            "仅内网可达的 Python AI 服务：模型网关、RAG、Agent 与知识管道。"
            "浏览器不直连本服务，对外协议由 Java ai-service 提供。"
        ),
        docs_url="/docs" if not settings.is_prod else None,
        redoc_url=None,
    )
    application.add_middleware(TraceIdMiddleware)
    application.include_router(api_router)

    logger.info(
        "ai service starting",
        extra={"service": settings.service_name, "version": __version__, "env": settings.app_env},
    )
    return application


app = create_app()
