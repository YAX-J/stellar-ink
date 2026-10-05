"""模型清单端点（`POST /provider/models`，仅内网可达，由 Java `ai-service` 转发）。

面板里的「添加模型」表单此前要手打模型名。这一层让它能**从供应商实时拉出来挑**：
请求里给 `provider` + `baseUrl`（+ 可选 `apiKey`），返回供应商自己那份 `/models` 清单。

四条刻意保持的口径：

1. **清单不来自我们**：没有任何厂商/模型名的默认值（AGENTS §5），拉不到就说拉不到 ——
   代码里预置一份的后果是「面板里明明有，一填就 404」，而那种错没人会想到来改代码。
2. **明文密钥只用一次**：`apiKey` 只为这一次请求存在（不落库、不进日志、不进审计、
   不进响应）—— 所以**响应里连掩码都没有**。留空时用该用户**自己**已保存的该角色密钥
   （`runtime.saved_config_for`：读同一张表、同一把主密钥，但**只认他自己的行** ——
   借站长那份全局配置的密钥去请求用户填的地址，等于让任何登录用户一次请求偷走一把 Key）。
3. **两处都没有密钥 → 400 + 可读提示**：让用户知道「要么填 Key，要么先去存一个」，
   而不是收到一个 401 让他以为自己填错了什么。
4. **失败分档沿用 `app/main.py` 的 `provider_error_status`**（不在这里另造一套）：
   400 参数/未配置、401 密钥无效、429 限流、502 不可达或不支持 `/models`。
   拉取失败**不改动任何已保存配置** —— 本端点一个字都不写。

为什么它是内部端点：`base_url` 是服务端拿去发请求的地址，让浏览器直连 Python 就等于把
「用我们的出口去打任意地址」这件事交给前端（SSRF）。对外那层（Java，登录即可用）
承担鉴权与公网地址校验，这里再按同一口径复校一次。
"""

import logging

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from app.core.internal_auth import InternalIdentity
from app.core.internal_auth_middleware import require_internal_identity
from app.providers import runtime
from app.providers.config_source import ProviderConfigError
from app.providers.errors import ProviderError
from app.providers.model_listing import list_provider_models
from app.providers.registry import capability_of
from app.schemas.common import AiErrorCode
from app.schemas.provider import ProviderModelEntry, ProviderModelsRequest, ProviderModelsResult

logger = logging.getLogger(__name__)

router = APIRouter(tags=["provider"])


class ApiKeyMissingError(RuntimeError):
    """请求里没带密钥，账号里也没有可用的已保存配置。

    **刻意是调用方能自己修的问题**（400）：加一个 Key，或先去面板存一个。
    把它报成 401 会让用户去怀疑「是不是我的会话/内部签名坏了」——
    方向完全跑偏，且他会反复重试一个再试一百次也不会变的结果。
    """


@router.post("/provider/models", summary="拉取供应商的模型清单（实时）", response_model=None)
async def list_models(
    request: ProviderModelsRequest,
    identity: InternalIdentity = Depends(require_internal_identity),  # noqa: B008 - 见 app/main.py
) -> ProviderModelsResult | JSONResponse:
    """按请求里的协议与地址实时拉一次模型清单。

    ⚠️ **不记调用账、不占配额**：拉清单不调用任何模型（零 Token、零费用），
    给它记一笔会让成本看板上的数字不再是「模型花了多少」——
    与 `/agent/verify`（零模型调用）同一条口径。
    """
    try:
        # 角色先校验：未知角色是**参数问题**，而 `capability_of` 抛的是装配期口径的
        # ProviderError（默认会落到 502）—— 这里按「谁该动手」改判成 400
        capability_of(request.role)
    except ProviderError as error:
        return _bad_request(str(error))

    try:
        api_key = _api_key_for(request, identity.user_id)
    except ApiKeyMissingError as error:
        return _bad_request(str(error))
    except ProviderConfigError as error:
        # 读不到已保存配置（库连不上、密文解不开）：同为「调用方能处理」的 400，
        # 且要说清是**读配置**失败 —— 否则用户会以为是自己填的地址不对
        return _bad_request(f"读取已保存的模型配置失败：{error}")

    listing = await list_provider_models(
        provider=request.provider,
        # `base_url` 可空（`fake` 不需要地址）：归一成空串再交给解析层，
        # 由 `url_policy` 对非 fake 协议给出「接口地址不能为空」这句可操作提示
        base_url=request.base_url or "",
        api_key=api_key,
    )

    # 审计只记「谁、哪个协议、哪个角色、拉到几条、有没有截断」。
    # ⚠️ **不记 base_url**：「绝不含密钥」这条得靠不加字段来保证 ——
    # 地址的路径部分可能被人塞进密钥，而它一旦进日志就再也收不回来。
    logger.info(
        "模型清单已拉取：userId=%s provider=%s role=%s models=%d truncated=%s",
        identity.user_id,
        request.provider,
        request.role,
        len(listing.models),
        listing.truncated,
    )
    return ProviderModelsResult(
        models=[
            ProviderModelEntry(id=model.id, created=model.created) for model in listing.models
        ],
        truncated=listing.truncated,
        source=listing.source,
    )


def _api_key_for(request: ProviderModelsRequest, user_id: int) -> str:
    """这一次请求要用的明文密钥：优先请求体，其次**该用户自己**已保存的该角色配置。

    ⚠️ **不回落站长那份全局配置**（`saved_config_for` 只读他自己的行）：本请求的 `base_url`
    是用户填的，借出全局密钥等于把站长的 Key 发往他控制的地址。没配过就让他填表单 ——
    400 的消息会这么说。

    `fake` 不需要密钥（它不发请求），因此不为它去读配置 —— 少一次库查询，
    也少一条「fake 也要填 Key」的误解。

    ⚠️ 返回值是明文。它只被交给 `list_provider_models` 拼 Authorization 头一次，
    本层不缓存、不打印、不回显。
    """
    explicit = (request.api_key or "").strip()
    if explicit:
        return explicit
    if request.provider == "fake":
        return ""

    saved = runtime.saved_config_for(user_id, request.role)
    if saved is None:
        raise ApiKeyMissingError(
            f"没有可用的 API Key：请求里没带，账号里也没有已保存的 {request.role} 配置"
            "（请在表单里填 API Key，或先在「AI 实验室 → 模型配置」里保存该角色）"
        )
    if not saved.api_key:
        raise ApiKeyMissingError(
            f"账号里已保存的 {request.role} 配置没有密钥（该配置的协议可能是 fake）："
            "请在表单里填 API Key"
        )
    return saved.api_key


def _bad_request(message: str) -> JSONResponse:
    """400 的统一形状：`{code, message}`（与其它内网端点一致，Java 侧照原话交给用户）。"""
    return JSONResponse(
        status_code=400,
        content={"code": AiErrorCode.BAD_REQUEST.value, "message": message},
    )
