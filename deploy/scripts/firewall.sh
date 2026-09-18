#!/usr/bin/env bash
# =============================================================================
# 星笺 STELLAR INK 源站防火墙（主机层，第二道防线）
#
# ⚠️ 读我之前先看这条：云安全组是第一道防线，而且**它拦得住 Docker**；
#    本脚本是主机层的第二道，主要用来堵一个坑——**ufw 拦不住 Docker 发布的端口**
#    （Docker 往 FORWARD 链里插自己的 ACCEPT 规则，绕过 ufw 的 INPUT 规则），
#    所以必须往 DOCKER-USER 链里写规则才能真正限制容器端口。
#
# 做完之后的效果：
#   · 22  只允许「你的管理机 IP」+ 当前 SSH 来源
#   · 80/443 只允许 Cloudflare 网段（网段从 deploy/docker/nginx/cloudflare_real_ip.inc 读，单一来源）
#   · 其余入站一律拒绝（3306/6379/6333/8848 本来就没发布端口，这里再加一道）
#   · loopback 发布的服务（127.0.0.1:3306 等）不受影响，SSH 隧道照用
#
# 用法（在服务器上，root 或 sudo）：
#   sudo bash deploy/scripts/firewall.sh --dry-run          # 只打印将要执行的规则，不改动
#   sudo bash deploy/scripts/firewall.sh                    # 应用（SSH 来源自动识别）
#   sudo bash deploy/scripts/firewall.sh --ssh-source 1.2.3.4
#   sudo bash deploy/scripts/firewall.sh --install-systemd  # 应用并装成开机自启的服务
#
# 🔴 锁死风险与自救：ufw 一旦启用，SSH 只允许上面那些来源。脚本会先放行当前 SSH 来源，
#    但**从本地控制台/其他 IP 跑脚本时务必显式传 --ssh-source**。真锁死了用云控制台的
#    VNC（网页终端）登录后执行：ufw disable
# =============================================================================
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CF_INC="${CF_INC:-$REPO_DIR/deploy/docker/nginx/cloudflare_real_ip.inc}"
UNIT_PATH="/etc/systemd/system/stellar-ink-firewall.service"
SELF_PATH="/usr/local/bin/stellar-ink-firewall"

DRY_RUN=0
INSTALL_SYSTEMD=0
SSH_SOURCE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run)          DRY_RUN=1 ;;
    --install-systemd)  INSTALL_SYSTEMD=1 ;;
    --ssh-source)       SSH_SOURCE="${2:-}"; shift ;;
    -h|--help)          sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（用 --help 看用法）"; exit 2 ;;
  esac
  shift
done

log() { echo "[$(date '+%F %T')] $*"; }
run() { if [ "$DRY_RUN" = "1" ]; then echo "  [dry-run] $*"; else eval "$@"; fi; }

[ "$(id -u)" = "0" ] || { echo "请用 root 或 sudo 运行"; exit 1; }
[ -f "$CF_INC" ] || { echo "找不到 Cloudflare 网段文件：$CF_INC"; exit 1; }

# —— 收集 Cloudflare 网段（set_real_ip_from 那些行就是权威列表）——
CF_V4=(); CF_V6=()
while read -r cidr; do
  case "$cidr" in
    *:*) CF_V6+=("$cidr") ;;
    *)   CF_V4+=("$cidr") ;;
  esac
done < <(grep -E '^\s*set_real_ip_from' "$CF_INC" | awk '{print $2}' | tr -d ';')
[ "${#CF_V4[@]}" -gt 0 ] || { echo "$CF_INC 里没解析到 IPv4 网段"; exit 1; }
log "Cloudflare 网段：IPv4 ${#CF_V4[@]} 条 / IPv6 ${#CF_V6[@]} 条"

# —— 管理来源：优先命令行，其次当前 SSH 连接 ——
if [ -z "$SSH_SOURCE" ] && [ -n "${SSH_CLIENT:-}" ]; then
  SSH_SOURCE="$(echo "$SSH_CLIENT" | awk '{print $1}')"
  log "从当前 SSH 连接识别到管理来源：$SSH_SOURCE"
fi
if [ -z "$SSH_SOURCE" ]; then
  cat <<'EOF'
❌ 没有管理来源 IP：既没传 --ssh-source，也不在 SSH 会话里。
   为避免把 SSH 关在门外，脚本拒绝继续。请二选一：
     sudo bash deploy/scripts/firewall.sh --ssh-source <你的固定公网IP>
     （在 SSH 会话里直接运行本脚本即可自动识别）
EOF
  exit 1
fi

# —— 安装 ufw ——
command -v ufw >/dev/null 2>&1 || run "apt-get update -qq && apt-get install -y ufw"

# —— 默认策略 ——
run "ufw default deny incoming"
run "ufw default allow outgoing"

# —— SSH：只放管理来源 ——
run "ufw allow from $SSH_SOURCE to any port 22 proto tcp comment 'stellar-ink: ssh from admin'"

# —— 80/443：只放 Cloudflare 网段 ——
for cidr in "${CF_V4[@]}"; do
  run "ufw allow from $cidr to any port 80  proto tcp comment 'stellar-ink: cf-origin'"
  run "ufw allow from $cidr to any port 443 proto tcp comment 'stellar-ink: cf-origin'"
done
for cidr in "${CF_V6[@]}"; do
  run "ufw allow from $cidr to any port 80  proto tcp comment 'stellar-ink: cf-origin'"
  run "ufw allow from $cidr to any port 443 proto tcp comment 'stellar-ink: cf-origin'"
done

# —— 允许 ICMP：保留路径 MTU 发现，避免大包黑洞（TLS 握手偶发失败常见原因之一）——
run "ufw allow proto icmp from any comment 'stellar-ink: pmtu'"

# —— 启用（--force 跳过交互确认）——
run "ufw --force enable"

# =============================================================================
# DOCKER-USER：ufw 拦不住 Docker 发布的端口，必须在这里补
# =============================================================================
PUBIF="$(ip -o -4 route show to default | awk '{print $5}' | head -1)"
[ -n "$PUBIF" ] || { echo "识别不到默认出口网卡，DOCKER-USER 部分跳过"; PUBIF=""; }
log "公网网卡：${PUBIF:-未识别}"

if [ -n "$PUBIF" ] && command -v iptables >/dev/null 2>&1; then
  CHAIN="STELLAR-INK-FW"
  # 幂等：先摘掉旧的挂载与链（dry-run 下不真正建链）
  if [ "$DRY_RUN" = "0" ]; then
    iptables -D DOCKER-USER -j "$CHAIN" 2>/dev/null || true
    iptables -N "$CHAIN" 2>/dev/null || iptables -F "$CHAIN"
  else
    echo "  [dry-run] iptables -D DOCKER-USER -j $CHAIN; iptables -N/-F $CHAIN"
  fi

  # 允许：管理来源 + Cloudflare 网段访问容器发布的 80/443
  run "iptables -A $CHAIN -i $PUBIF -p tcp -m multiport --dports 80,443 -s $SSH_SOURCE -j RETURN"
  for cidr in "${CF_V4[@]}" "${CF_V6[@]}"; do
    run "iptables -A $CHAIN -i $PUBIF -p tcp -m multiport --dports 80,443 -s $cidr -j RETURN"
  done
  # 其余打到 80/443 的公网流量：丢弃
  run "iptables -A $CHAIN -i $PUBIF -p tcp -m multiport --dports 80,443 -j DROP"
  # 其它端口交回 Docker 自己的规则（loopback 发布的端口不经这里）
  run "iptables -A $CHAIN -j RETURN"
  run "iptables -I DOCKER-USER 1 -j $CHAIN"
fi

# —— 可选：装成开机自启（Docker 重启会重建 DOCKER-USER，需要重跑本脚本）——
if [ "$INSTALL_SYSTEMD" = "1" ] && [ "$DRY_RUN" = "0" ]; then
  log "安装 systemd 单元：$UNIT_PATH"
  install -m 755 "$0" "$SELF_PATH"
  cat > "$UNIT_PATH" <<EOF
[Unit]
Description=stellar-ink host firewall (ufw + DOCKER-USER allowlist)
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
Environment=SSH_CLIENT=${SSH_SOURCE} 0 0
ExecStart=/bin/bash ${SELF_PATH} --ssh-source ${SSH_SOURCE}

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable --now stellar-ink-firewall.service
  log "已启用 stellar-ink-firewall.service（开机自动重建规则）"
fi

# —— 结果 ——
if [ "$DRY_RUN" = "1" ]; then
  log "dry-run 结束：以上是将会执行的规则，未做任何改动"
else
  log "完成。当前状态："
  ufw status verbose | sed 's/^/  /'
  echo "  --- DOCKER-USER 链 ---"
  iptables -S DOCKER-USER 2>/dev/null | sed 's/^/  /' || true
  echo
  echo "  ⚠️ 上面若看不到你的管理 IP 的 22 规则，立刻执行：ufw disable 或云控制台 VNC 登录抢救"
fi
