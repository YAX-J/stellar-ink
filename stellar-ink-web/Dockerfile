# syntax=docker/dockerfile:1
# =============================================================================
# 星笺 STELLAR INK 前端镜像：Vite 构建 → Nginx 托管 SPA 并反代网关
# （构建上下文为仓库根目录，nginx 配置取自 deploy/docker/nginx/default.conf）
# =============================================================================
FROM node:22-alpine AS build

WORKDIR /web
COPY stellar-ink-web/package.json stellar-ink-web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund
COPY stellar-ink-web/ ./
RUN npm run build

FROM nginx:1.27-alpine
ENV TZ=Asia/Shanghai
COPY deploy/docker/nginx/default.conf /etc/nginx/conf.d/default.conf
# 反代公共指令片段（被 default.conf 中多个 location 通过 include 引用）
COPY deploy/docker/nginx/api_proxy.inc /etc/nginx/conf.d/api_proxy.inc
COPY --from=build /web/dist /usr/share/nginx/html

EXPOSE 80
