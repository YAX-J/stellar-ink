"""星笺 AI 编排服务入口。

启动（本地）::

    uv run uvicorn app.main:app --host 127.0.0.1 --port 8200

边界（与 docs/ai/fast-track-plan.md 对齐）：
- 仅内网可达；浏览器不直连，对外协议由 Java `ai-service` 提供。
- 除探活/文档外，所有路径都要求 Java 侧签发的 `X-AI-*` 签名头；
  身份（userId/role）参与签名，因此内网也无法靠改头冒充更高角色。
- 未配置 `AI_INTERNAL_SECRET` 时**拒绝**受保护请求（fail-closed），不放行。
"""

import logging

from fastapi import APIRouter, Depends, FastAPI, Request
from fastapi.responses import JSONResponse

from app import __version__
from app.api.v1 import api_router
from app.core.config import get_settings
from app.core.internal_auth import (
    InternalAuthError,
    InternalIdentity,
    InternalRequestVerifier,
    load_internal_secret,
)
from app.core.internal_auth_middleware import InternalAuthMiddleware, require_internal_identity
from app.core.logging import configure_logging
from app.core.trace import TraceIdMiddleware, current_trace_id
from app.schemas.common import AiErrorCode

logger = logging.getLogger(__name__)

#: 自检路由：只在非生产暴露，用来验证「签名链路真的通了」。
#: 它返回的正是验签后的身份 —— 一眼就能看出是「谁」在调用，而不是只能看日志。
SELF_CHECK_PATH = "/internal/whoami"

internal_router = APIRouter(tags=["internal"])


@internal_router.get(SELF_CHECK_PATH, summary="内部签名自检（仅非生产）")
async def whoami(
    # FastAPI 的依赖注入就是靠参数默认值声明，B008 对它是误报
    identity: InternalIdentity = Depends(require_internal_identity),  # noqa: B008
) -> dict[str, object]:
    return {
        "userId": identity.user_id,
        "role": identity.role,
        "traceId": identity.trace_id,
        "note": "本接口仅验证内部签名链路；正式能力在 /qa、/writing、/admin 前缀下",
    }


def build_verifier() -> InternalRequestVerifier | None:
    """按环境变量装配验签器；未配置时返回 None（中间件会据此拒绝受保护请求）。"""
    try:
        return InternalRequestVerifier(load_internal_secret())
    except InternalAuthError as error:
        # 只记原因，不打印任何密钥内容；服务仍需能起来（探活要可用，便于发现配置问题）
        logger.warning("内部验签不可用：%s（受保护接口将返回 401）", error)
        return None


def create_app(verifier: InternalRequestVerifier | None = None) -> FastAPI:
    """应用工厂。

    ``verifier`` 显式传入时优先使用（测试与将来从配置中心装配）；
    为 None 时按环境变量自建 —— 注意「显式传 None」与「不传」在此等价，
    因此测试要表达「未配置密钥」时直接构造不传参的 app 即可。
    """
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
    resolved_verifier = verifier if verifier is not None else build_verifier()

    # 中间件顺序：先追溯 traceId，再验签（验签失败日志里也要有 traceId 才能对读两侧日志）
    application.add_middleware(InternalAuthMiddleware, verifier=resolved_verifier)
    application.add_middleware(TraceIdMiddleware)
    application.include_router(api_router)
    if not settings.is_prod:
        application.include_router(internal_router)

    @application.exception_handler(InternalAuthError)
    async def handle_internal_auth_error(_: Request, error: InternalAuthError) -> JSONResponse:
        # 原因进服务端日志（含 traceId 便于与 Java 侧对读），响应只给可展示的提示
        logger.warning("内部验签失败：%s", error.reason)
        return JSONResponse(
            status_code=401,
            content={
                "code": AiErrorCode.UNAUTHORIZED.value,
                "message": f"内部请求校验失败：{error.reason}",
                "traceId": current_trace_id(),
            },
        )

    logger.info(
        "ai service starting",
        extra={
            "service": settings.service_name,
            "version": __version__,
            "env": settings.app_env,
            "internalAuth": "configured" if resolved_verifier else "missing-secret",
        },
    )
    return application


app = create_app()
