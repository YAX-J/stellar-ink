#!/bin/bash
# 首次建库与已有库补齐共用同一份清单；任何脚本失败立即停止。
# 官方 entrypoint 可能 source 本文件，用子 shell 避免改变它的选项与密码环境。
(
set -euo pipefail
sql_dir="${AI_SQL_DIR:-/stellar-sql}"
export MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?必须设置 MYSQL_ROOT_PASSWORD}"
while IFS= read -r migration || [ -n "$migration" ]; do
  migration="${migration%$'\r'}"
  [ -n "$migration" ] || continue
  echo "执行 AI 数据库迁移：$migration"
  mysql --protocol=socket --user=root --default-character-set=utf8mb4 \
    --database="${MYSQL_DATABASE:?必须设置 MYSQL_DATABASE}" < "$sql_dir/$migration"
done < "$sql_dir/ai-migrations.txt"
)
