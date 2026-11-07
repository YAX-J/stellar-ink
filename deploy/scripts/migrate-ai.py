"""已有库补齐 AI 表：凭据只从 MYSQL_* 环境变量读取，不打印密码。

执行前先创建并选好目标库；本脚本不删除数据、不导入种子、不运行博客历史 ALTER。
Windows / Linux 都可用：python deploy/scripts/migrate-ai.py
"""

import os
from pathlib import Path
import subprocess


def main() -> None:
    sql_dir = Path(__file__).resolve().parents[1] / "sql"
    database = os.environ.get("MYSQL_DB")
    if not database:
        raise SystemExit("请显式设置 MYSQL_DB，避免迁移到错误的数据库")
    files = (sql_dir / "ai-migrations.txt").read_text(encoding="utf-8").splitlines()
    command = [
        os.environ.get("MYSQL_CLIENT", "mysql"),
        "--default-character-set=utf8mb4",
        "--connect-timeout=3",
        "--host=" + os.environ.get("MYSQL_HOST", "127.0.0.1"),
        "--port=" + os.environ.get("MYSQL_PORT", "3306"),
        "--user=" + os.environ.get("MYSQL_USER", "root"),
        "--database=" + database,
    ]
    env = {**os.environ, "MYSQL_PWD": os.environ.get("MYSQL_PASSWORD", "")}
    for name in files:
        if not name or Path(name).name != name or not name.endswith(".sql"):
            raise SystemExit("AI 迁移清单包含无效文件名")
        print("执行 AI 数据库迁移：" + name, flush=True)
        with (sql_dir / name).open("rb") as source:
            subprocess.run(command, stdin=source, env=env, check=True)
    print("AI 数据库迁移完成；可重复执行，不会重置已有配置和内容。")


if __name__ == "__main__":
    main()
