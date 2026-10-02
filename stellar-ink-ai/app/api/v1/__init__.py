"""v1 路由聚合：新增领域路由在这里挂载。"""

from fastapi import APIRouter

from app.api.v1 import agent as agent_routes
from app.api.v1 import eval as eval_routes
from app.api.v1 import health
from app.api.v1 import mcp as mcp_routes
from app.api.v1 import memory as memory_routes
from app.api.v1 import qa as qa_routes
from app.api.v1 import style as style_routes
from app.api.v1 import trace as trace_routes
from app.api.v1 import wiki as wiki_routes
from app.api.v1 import writing as writing_routes

api_router = APIRouter()
api_router.include_router(health.router)
# 评测接口与问答/Copilot/画像/Agent/MCP/回放接口都受内部签名保护（不在 PUBLIC_PATHS 白名单里），
# 对外由 ai-service 的门槛（ADMIN / 登录 / AUTHOR）转发
api_router.include_router(eval_routes.router)
api_router.include_router(qa_routes.router)
api_router.include_router(writing_routes.router)
api_router.include_router(style_routes.router)
api_router.include_router(agent_routes.router)
api_router.include_router(mcp_routes.router)
# 按 traceId 回放（E3-4）：运维排障用，同样只在编排网络内可达
api_router.include_router(trace_routes.router)
# LLM Wiki（E4）：从带证据的主张抽取起步；落库与读者侧页面在 Java
api_router.include_router(wiki_routes.router)
# 作者记忆（M9）：Python 只出判断（候选/计划/召回集合），落库与状态流转在 Java
api_router.include_router(memory_routes.router)
