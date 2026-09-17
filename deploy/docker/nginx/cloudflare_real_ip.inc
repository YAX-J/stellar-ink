# =============================================================================
# Cloudflare 真实客户端 IP 还原（http 上下文，由 default.conf 在 server 之前 include）
#
# 为什么必须配：站点在 Cloudflare 后面时，TCP 来源都是 CF 边缘节点，nginx 的
#   $remote_addr 会变成 CF 的 IP。而 default.conf 里三个 limit_req_zone 是按
#   $binary_remote_addr 限流的 —— 不还原就等于把全天下的访客当成同一个人，
#   结果是全站 429，日志里也看不到真实来源。
# 还原之后：$remote_addr / $binary_remote_addr = 真实访客 IP，
#   X-Real-IP、X-Forwarded-For 也会带着真实 IP 转发给网关。
#
# 网段来源：https://www.cloudflare.com/ips-v4 与 https://www.cloudflare.com/ips-v6
# 取值日期：2026-09-17。Cloudflare 会新增网段，建议每季度对一次；漏配的表现是
#   部分访客被当成 CF 的 IP（限流串号、日志里是 CF 网段）。
# =============================================================================

# ---- IPv4 ----
set_real_ip_from 173.245.48.0/20;
set_real_ip_from 103.21.244.0/22;
set_real_ip_from 103.22.200.0/22;
set_real_ip_from 103.31.4.0/22;
set_real_ip_from 141.101.64.0/18;
set_real_ip_from 108.162.192.0/18;
set_real_ip_from 190.93.240.0/20;
set_real_ip_from 188.114.96.0/20;
set_real_ip_from 197.234.240.0/22;
set_real_ip_from 198.41.128.0/17;
set_real_ip_from 162.158.0.0/15;
set_real_ip_from 104.16.0.0/13;
set_real_ip_from 104.24.0.0/14;
set_real_ip_from 172.64.0.0/13;
set_real_ip_from 131.0.72.0/22;

# ---- IPv6 ----
set_real_ip_from 2400:cb00::/32;
set_real_ip_from 2606:4700::/32;
set_real_ip_from 2803:f800::/32;
set_real_ip_from 2405:b500::/32;
set_real_ip_from 2405:8100::/32;
set_real_ip_from 2a06:98c0::/29;
set_real_ip_from 2c0f:f248::/32;

# Cloudflare 回源时把真实访客 IP 放在这个头里（不是 X-Forwarded-For）
real_ip_header CF-Connecting-IP;
