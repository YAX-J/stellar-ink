# syntax=docker/dockerfile:1
# =============================================================================
# 星笺 Python AI 编排服务（FastAPI + uvicorn，仅内网可达，不对外发布端口）
#
# - 用官方 uv 镜像：依赖按 `uv.lock` 安装（--frozen），构成本身可复现
# - 先只 COPY 依赖清单再装依赖：改源码不会让这一层失效（重装依赖是构建里最慢的一步）
# - **不 COPY .env**：密钥一律由 compose 从环境变量注入（见 stellar-ink-ai/.dockerignore）
# - 健康检查用 Python 自带的 urllib：镜像里没有 curl，为一条探活装它不值得
# =============================================================================
FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim

ENV TZ=Asia/Shanghai \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy \
    UV_COMPILE_BYTECODE=1

WORKDIR /app

# ① 依赖层：只随 pyproject/uv.lock 变化
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev --no-install-project

# ② 源码层
COPY . ./

EXPOSE 8200

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=6 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8200/health', timeout=3).status == 200 else 1)"

# 直接调 .venv 里的 uvicorn：`uv run` 每次都要重新解析依赖，容器里没必要
CMD ["/app/.venv/bin/uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8200"]
