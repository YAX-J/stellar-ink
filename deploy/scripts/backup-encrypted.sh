#!/usr/bin/env bash
# age 整包加密；公钥用于备份，私钥留在独立恢复环境。
set -euo pipefail
umask 077
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_DIR="${BACKUP_COMPOSE_DIR:-$REPO_DIR/deploy/docker}"
BACKUP_ROOT="${BACKUP_ROOT:-/data/stellar-ink/backups}"
METRICS_DIR="${BACKUP_METRICS_DIR:-$COMPOSE_DIR/data/node-exporter}"
KEEP="${BACKUP_KEEP_DAYS:-7}"
DELIVERY="${BACKUP_DELIVERY_MODE:-push}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
STAGE=""
PARTIAL=""
mkdir -p "$BACKUP_ROOT" "$METRICS_DIR"
# 指标不含敏感信息，允许非 root 的 node-exporter 遍历目录。
chmod 755 "$METRICS_DIR"
exec 9>"$BACKUP_ROOT/.backup.lock"
flock -n 9 || { echo '已有备份正在执行'; exit 1; }
metric() {
  printf '%s %s\n' "$1" "$2" > "$METRICS_DIR/$1.prom.tmp"
  chmod 644 "$METRICS_DIR/$1.prom.tmp"
  mv "$METRICS_DIR/$1.prom.tmp" "$METRICS_DIR/$1.prom"
}
cleanup() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    metric stellar_backup_last_run_success 0
    echo '备份失败，上一份成功备份保留。' >&2
  fi
  [ -z "$PARTIAL" ] || rm -f -- "$PARTIAL"
  [ -z "$STAGE" ] || rm -rf -- "$STAGE"
}
trap cleanup EXIT
metric stellar_backup_last_attempt_timestamp_seconds "$(date +%s)"
[[ "$KEEP" =~ ^[1-9][0-9]*$ ]] || { echo '保留天数必须为正整数'; exit 1; }
for tool in age tar gzip sha256sum python3; do command -v "$tool" >/dev/null; done
: "${BACKUP_RECIPIENTS_FILE:?设置 age 公钥文件}"
[ -s "$BACKUP_RECIPIENTS_FILE" ]
[ -f "$COMPOSE_DIR/.env" ]
[[ "$DELIVERY" = push || "$DELIVERY" = pull ]] || { echo '备份交付方式仅支持 push 或 pull'; exit 1; }
if [ "$DELIVERY" = pull ] && [ -n "${BACKUP_REMOTE:-}" ]; then
  echo 'Windows 拉取模式不应设置 BACKUP_REMOTE'; exit 1
fi
if [ "$DELIVERY" = push ] && [ -z "${BACKUP_REMOTE:-}" ] && [ "${BACKUP_ALLOW_LOCAL_ONLY:-0}" != 1 ]; then
  echo '必须设置 BACKUP_REMOTE；本地演练可显式 BACKUP_ALLOW_LOCAL_ONLY=1' >&2
  exit 1
fi
if [ -n "${BACKUP_REMOTE:-}" ]; then command -v rsync >/dev/null; fi
STAGE="$(mktemp -d "$BACKUP_ROOT/.staging.XXXXXXXX")"
PARTIAL="$BACKUP_ROOT/stellar-$STAMP.tar.age.partial"
if [ "${BACKUP_MYSQL_MODE:-compose}" = host ]; then
  : "${BACKUP_MYSQL_CNF:?宿主机 MySQL 需要私有客户端配置文件}"
  : "${MYSQL_DB:?请设置 MYSQL_DB}"
  mysqldump --defaults-extra-file="$BACKUP_MYSQL_CNF" --single-transaction --routines --events \
    --no-tablespaces --default-character-set=utf8mb4 "$MYSQL_DB" | gzip > "$STAGE/mysql.sql.gz"
else
  docker compose --project-directory "$COMPOSE_DIR" -f "$COMPOSE_DIR/docker-compose.yml" exec -T mysql \
    sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --events --no-tablespaces --default-character-set=utf8mb4 "$MYSQL_DATABASE"' \
    | gzip > "$STAGE/mysql.sql.gz"
fi
gzip -t "$STAGE/mysql.sql.gz"
cp "$COMPOSE_DIR/.env" "$STAGE/env.backup"
for relative in data/uploads data/qa-eval-reports data/certs observability/alertmanager/alertmanager.yml observability/alertmanager/secrets; do
  if [ -e "$COMPOSE_DIR/$relative" ]; then
    mkdir -p "$STAGE/$(dirname "$relative")"
    cp -a "$COMPOSE_DIR/$relative" "$STAGE/$relative"
  fi
done
cp "$COMPOSE_DIR/docker-compose.yml" "$STAGE/compose.snapshot.yml"
python3 "$REPO_DIR/deploy/scripts/restore-backup.py" manifest "$STAGE"
tar czf - -C "$STAGE" . | age -R "$BACKUP_RECIPIENTS_FILE" -o "$PARTIAL"
FINAL="$BACKUP_ROOT/stellar-$STAMP.tar.age"
mv "$PARTIAL" "$FINAL"
PARTIAL=""
(cd "$BACKUP_ROOT" && sha256sum "$(basename "$FINAL")") > "$FINAL.sha256"
if [ -n "${BACKUP_REMOTE:-}" ]; then
  rsync -azs -- "$FINAL" "$FINAL.sha256" "$BACKUP_REMOTE/"
fi
# Windows 拉取模式只记录本机生成时间，下载、校验并回执后才记异地成功。
metric stellar_backup_last_created_timestamp_seconds "$(date +%s)"
if [ "$DELIVERY" = push ] && [ -n "${BACKUP_REMOTE:-}" ]; then
  metric stellar_backup_last_success_timestamp_seconds "$(date +%s)"
fi
metric stellar_backup_last_run_success 1
find "$BACKUP_ROOT" -maxdepth 1 -type f -name 'stellar-*.tar.age*' -mtime +"$KEEP" -delete
echo "备份完成：$FINAL"
