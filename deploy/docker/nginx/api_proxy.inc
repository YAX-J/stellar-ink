# =============================================================================
# 网关反代的公共指令片段（被 default.conf 的多个 location 复用）
# 说明：本文件扩展名为 .inc，不在 conf.d/*.conf 的自动加载范围内，
#       只能通过 include 显式引入，因此不会被误当成独立 server 配置。
# =============================================================================
set $gateway http://gateway:8080;
proxy_pass $gateway;
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_connect_timeout 5s;
proxy_read_timeout 60s;
