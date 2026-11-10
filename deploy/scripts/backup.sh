#!/usr/bin/env bash
# 兼容既有定时任务入口，全部转到加密备份实现。
set -euo pipefail
exec bash "$(dirname "${BASH_SOURCE[0]}")/backup-encrypted.sh" "$@"
