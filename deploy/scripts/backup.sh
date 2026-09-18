#!/usr/bin/env bash
# =============================================================================
# 星笺 STELLAR INK 每日备份
#
# 备份内容：
#   1) MySQL 逻辑备份（mysqldump --single-transaction，不锁表）
#   2) 上传文件（deploy/docker/data/uploads，头像等）
#   3) .env（含 JWT 密钥与数据库口令 —— 恢复时缺了它，数据库备份也用不起来）
#
# 不备份：Qdrant（AI 阶段可由 MySQL 重建索引）、Nacos（prod 不 import Nacos 配置，
#         注册表是临时数据）、Redis（缓存，丢了只是所有人重新登录一次）。
#
# 用法：
#   bash deploy/scripts/backup.sh
#   BACKUP_REMOTE=ubuntu@1.2.3.4:/backup/stellar bash deploy/scripts/backup.sh   # 顺带同步到异地
#
# 建议 cron（每天 03:00，把 BACKUP_REMOTE 换成你的测试/备份机）：
#   0 3 * * * BACKUP_REMOTE=ubuntu@1.2.3.4:/backup/stellar /bin/bash /app/stellar-ink/deploy/scripts/backup.sh >> /data/stellar-ink/backups/backup.log 2>&1
#
# 环境变量：
#   BACKUP_ROOT    本地备份目录，默认 /data/stellar-ink/backups
#   KEEP           保留天数，默认 7
#   BACKUP_REMOTE  rsync 目标（形如 user@host:/path），留空则只做本地备份
# =============================================================================
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_DIR="$REPO_DIR/deploy/docker"
BACKUP_ROOT="${BACKUP_ROOT:-/data/stellar-ink/backups}"
KEEP="${KEEP:-7}"
STAMP="$(date +%F_%H%M)"
DEST="$BACKUP_ROOT/$STAMP"

log() { echo "[$(date '+%F %T')] $*"; }

[ -f "$COMPOSE_DIR/.env" ] || { log "❌ 找不到 $COMPOSE_DIR/.env，先在 deploy/docker 下创建它"; exit 1; }
mkdir -p "$DEST"

# —— 1) MySQL ——
log "导出 MySQL 到 $DEST/mysql.sql.gz ..."
docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T mysql \
  sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events \
         --default-character-set=utf8mb4 "$MYSQL_DATABASE"' | gzip > "$DEST/mysql.sql.gz"

# 大小自检：容器没起来/认证失败时会产出空文件，宁可直接报错也不要留个假备份
if [ ! -s "$DEST/mysql.sql.gz" ] || [ "$(stat -c%s "$DEST/mysql.sql.gz")" -lt 1000 ]; then
  log "❌ MySQL 备份异常（文件为空或过小），已删除，本次备份失败"
  rm -f "$DEST/mysql.sql.gz"
  exit 1
fi

# —— 2) 上传文件 ——
if [ -d "$COMPOSE_DIR/data/uploads" ]; then
  log "打包上传文件 ..."
  tar czf "$DEST/uploads.tar.gz" -C "$COMPOSE_DIR/data" uploads
else
  log "⚠️  $COMPOSE_DIR/data/uploads 不存在，跳过（STORAGE_TYPE=cos 时属正常）"
fi

# —— 3) .env（含密钥，注意保管）——
cp "$COMPOSE_DIR/.env" "$DEST/env.backup"
chmod 600 "$DEST/env.backup" "$DEST"/*.gz 2>/dev/null || true

# —— 4) 清理过期备份 ——
log "清理 ${KEEP} 天前的备份 ..."
find "$BACKUP_ROOT" -maxdepth 1 -mindepth 1 -type d -name '20*' -mtime +"$KEEP" -exec rm -rf {} + 2>/dev/null || true

# —— 5) 同步到异地（测试/备份机）——
if [ -n "${BACKUP_REMOTE:-}" ]; then
  log "同步到 $BACKUP_REMOTE ..."
  rsync -az "$DEST" "$BACKUP_REMOTE/"
else
  log "⚠️  未设置 BACKUP_REMOTE，跳过异地同步（强烈建议设成你那台腾讯云测试机）"
fi

log "✅ 完成：$DEST（$(du -sh "$DEST" | cut -f1)）"
