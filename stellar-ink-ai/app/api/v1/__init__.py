"""v1 路由聚合：新增领域路由在这里挂载。"""

from fastapi import APIRouter

from app.api.v1 import agent as agent_routes
from app.api.v1 import eval as eval_routes
from app.api.v1 import health
from app.api.v1 import qa as qa_routes
from app.api.v1 import style as style_routes
from app.api.v1 import writing as writing_routes

api_router = APIRouter()
api_router.include_router(health.router)
# 评测接口与问答/Copilot/画像/Agent 接口都受内部签名保护（不在 PUBLIC_PATHS 白名单里），
# 对外由 ai-service 的门槛（ADMIN / 登录 / AUTHOR）转发
api_router.include_router(eval_routes.router)
api_router.include_router(qa_routes.router)
api_router.include_router(writing_routes.router)
api_router.include_router(style_routes.router)
api_router.include_router(agent_routes.router)
