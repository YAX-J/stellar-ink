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
# ⚠️ 这里**不要**写 proxy_read_timeout：nginx 不允许同一个 context 里重复定义它，
#    而 default.conf 的 /ai location 需要 120s（真模型跑一整段回答可能超过 60s）。
#    写死在公共片段里、和 location 内的 120s 撞上，nginx 会直接拒绝启动：
#      [emerg] "proxy_read_timeout" directive is duplicate in /etc/nginx/conf.d/default.conf
#    （2026-10-05 真实事故：web 容器重启循环、443 无人监听）。
#    不写它 = 用 nginx 默认的 60s，与原来对其它 location 的效果完全一致。
#    需要更长超时的 location 请自己声明（参考 default.conf 的 /ai 块）。
