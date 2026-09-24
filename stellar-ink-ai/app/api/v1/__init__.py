"""v1 路由聚合：新增领域路由在这里挂载。"""

from fastapi import APIRouter

from app.api.v1 import health

api_router = APIRouter()
api_router.include_router(health.router)
