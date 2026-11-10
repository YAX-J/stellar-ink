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

# 公开 HTML 服务：只有 Node 标准库与共用纯函数，无 node_modules、无构建期业务数据。
FROM node:22-alpine AS renderer
WORKDIR /web
COPY --from=build /web/dist ./dist
COPY stellar-ink-web/server ./server
COPY stellar-ink-web/src/utils/markdown.js stellar-ink-web/src/utils/seo.js ./src/utils/
COPY stellar-ink-web/package.json ./package.json
USER node
EXPOSE 4180
CMD ["node", "server/http.mjs"]

FROM nginx:1.27-alpine AS web
ENV TZ=Asia/Shanghai
COPY deploy/docker/nginx/default.conf /etc/nginx/conf.d/default.conf
# 反代公共指令片段（被 default.conf 中多个 location 通过 include 引用）
COPY deploy/docker/nginx/api_proxy.inc /etc/nginx/conf.d/api_proxy.inc
# Cloudflare 真实客户端 IP 还原（CF 全部网段 + CF-Connecting-IP），必须在边缘限流之前生效
COPY deploy/docker/nginx/cloudflare_real_ip.inc /etc/nginx/conf.d/cloudflare_real_ip.inc
COPY --from=build /web/dist /usr/share/nginx/html

EXPOSE 80 443
