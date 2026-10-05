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
from app.providers.errors import (
    InvalidBaseUrlError,
    ProviderAuthError,
    ProviderError,
    ProviderRateLimitError,
    UnsupportedCapabilityError,
)
from app.schemas.common import AiErrorCode

logger = logging.getLogger(__name__)

#: 自检路由：只在非生产暴露，用来验证「签名链路真的通了」。
#: 它返回的正是验签后的身份 —— 一眼就能看出是「谁」在调用，而不是只能看日志。
SELF_CHECK_PATH = "/internal/whoami"

internal_router = APIRouter(tags=["internal"])


def provider_error_status(error: ProviderError) -> int:
    """Provider 失败的 HTTP 状态码：**按「谁该动手」分档，不按异常名字分档**。

    - 429：被限流（上游让我们慢一点，可退避重试）；
    - 400：参数/配置问题（角色没配、能力不符、地址不合规）—— 让用户去面板改，
      重试一万次也没用；
    - 401：**密钥无效**（上游 401/403）—— 同上，只有人类能修（换一把 Key）；
    - 502：上游坏了（超时 / 5xx / 连不上）—— 这是「服务下游的问题」，不是请求错了。

    为什么必须有这一层：真实模型接上之后，超时与限流是**常态**。
    没有它，一次模型超时就是一个带栈的 500，前端只能显示「服务器错误」，
    而用户真正需要知道的是「这次是模型超时，可以重试」。

    ⚠️ 三条新增分档（拉模型清单那一刀）说明：

    * `ProviderAuthError → 401`：在此之前它落进默认的 502，但 502 的含义是「我们这边坏了」，
      而密钥无效**只有用户能修**。401 与 502 在 Java 侧都翻成同一条
      `ErrorCode.SERVICE_UNAVAILABLE` + 原话提示（见 `PythonErrorDecoder`），
      所以对前端而言这条改动**不会**变成「登录失效」——它仍然带上游那句可操作的话。
    * `InvalidBaseUrlError → 400`：地址不合规是**用户填错**（个人配置只允许公网，
      见 `providers/url_policy.py`）。它此前只在装配路径出现，而那条路径本来就转成 400
      （`assembly_error`），这里只是把同一档口径收进唯一的分档函数。
    * `ProviderRateLimitError` 必须先判：`ProviderQuotaExhaustedError` 是它的子类（仍是 429）。
    """
    if isinstance(error, ProviderRateLimitError):
        return 429
    if isinstance(error, (UnsupportedCapabilityError, InvalidBaseUrlError)):
        return 400
    if isinstance(error, ProviderAuthError):
        return 401
    return 502


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

    @application.exception_handler(ProviderError)
    async def handle_provider_error(_: Request, error: ProviderError) -> JSONResponse:
        """模型调用的统一出口。

        放在全局而不是每个端点各写一遍：问答、Copilot、Agent、评测都会调模型，
        逐端点写必然漏掉一处，而漏掉的那处就是「带栈的 500」。
        响应体用 `to_public_dict()` —— 它刻意不含密钥、内网地址与上游原始报文；
        可读原因里那句「请在 AI 实验室里填该角色」是给人看的，不是给运维看的。
        """
        payload = {**error.to_public_dict(), "traceId": current_trace_id()}
        logger.warning("模型调用失败：%s", payload)
        return JSONResponse(status_code=provider_error_status(error), content=payload)

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
