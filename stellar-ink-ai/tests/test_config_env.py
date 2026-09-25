"""配置加载的自检：`.env` 必须同时喂到 Settings 字段**与** `os.environ`。

守的是一个真实踩到的坑：`pydantic-settings` 只把 `.env` 里的键灌进 `Settings` 字段，
**不会**放进 `os.environ`。而本仓库的密钥（`AI_INTERNAL_SECRET`、`AI_SECRET_MASTER_KEY`）
是从 `os.environ` 直读的 —— 于是「把密钥写进 `.env`」这件事不会自动成立：
`AI_PORT` 生效了、密钥却读不到，看起来像密钥填错了，排查方向会跑到 Java 侧去。

用子进程验证是刻意的：`app.core.config` 在 **import 期**加载 `.env`，
而测试进程里该模块早已导入（pytest 自己也导过），在进程内 reload 会污染
其他测试的单例与 `os.environ`。子进程能给一个干净的起点，也顺带证明了
「从任意 cwd 启动都能读到这份 `.env`」。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SECRET = "stellar-ink-internal-secret-for-test-only-0001"
#: 合法的 AES-256 主密钥（base64 解出 32 字节）
MASTER_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="


def run_probe(env_file: Path, cwd: Path, extra_env: dict[str, str] | None = None) -> dict:
    """在一个干净的子进程里加载配置并回报结论（**不打印任何值**）。"""
    del env_file  # 只用来说明意图：被测的是项目根那份固定路径的 .env
    snippet = """
import json, os, sys
from app.core.config import ENV_FILE, env_source, get_settings
from app.core.internal_auth import load_internal_secret
from app.core.crypto import load_master_key

settings = get_settings()
report = {
    "env_file": str(ENV_FILE),
    "env_source": env_source(),
    "port": settings.port,
    "log_level": settings.log_level,
    "secret_in_environ": "AI_INTERNAL_SECRET" in os.environ,
    "secret_value_matches": False,
    "master_key_bytes": 0,
}
try:
    report["secret_value_matches"] = load_internal_secret() == os.environ.get("PROBE_EXPECTED", "")
except Exception as error:
    report["secret_error"] = type(error).__name__
try:
    report["master_key_bytes"] = len(load_master_key() or b"")
except Exception as error:
    report["master_key_error"] = type(error).__name__
print(json.dumps(report))
"""
    # **继承**父进程环境再改，而不是从零构造：Windows 上 `SYSTEMROOT` 之类缺失会让
    # 子进程连标准库都 import 不了（表现为一个没有 traceback 的退出码 1，很难查）。
    # 只清掉可能干扰的 AI_* 与 PROBE_*，保证「.env 说了算」。
    env = {key: value for key, value in os.environ.items() if not key.startswith(("AI_", "PROBE_"))}
    env["PYTHONPATH"] = str(PROJECT_ROOT)
    env["PROBE_EXPECTED"] = SECRET
    env.update(extra_env or {})
    completed = subprocess.run(  # noqa: S603 - 固定命令，无外部输入
        [sys.executable, "-c", snippet],
        cwd=str(cwd),
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=True,
    )
    return json.loads(completed.stdout.strip().splitlines()[-1])


@pytest.fixture()
def probe_env(tmp_path: Path) -> Path:
    """在项目根写一份临时 .env，并把原文件挪开（结束时还原）。"""
    target = PROJECT_ROOT / ".env"
    backup = None
    if target.exists():
        backup = target.read_text(encoding="utf-8")
    target.write_text(
        f"AI_INTERNAL_SECRET={SECRET}\n"
        f"AI_SECRET_MASTER_KEY={MASTER_KEY}\n"
        "AI_PORT=8201\n"
        "AI_LOG_LEVEL=DEBUG\n",
        encoding="utf-8",
    )
    try:
        yield target
    finally:
        if backup is None:
            target.unlink(missing_ok=True)
        else:
            target.write_text(backup, encoding="utf-8")


def test_env_file_feeds_both_settings_and_environ(probe_env: Path, tmp_path: Path) -> None:
    """两条路都要通：Settings 字段（端口/日志）与 os.environ（密钥）。"""
    report = run_probe(probe_env, tmp_path)

    # Settings 字段
    assert report["port"] == 8201
    assert report["log_level"] == "DEBUG"
    assert report["env_source"].endswith("（存在）")

    # os.environ 直读的密钥 —— 这一条就是当初缺的东西
    assert report["secret_in_environ"] is True, "AI_INTERNAL_SECRET 没有进 os.environ"
    assert report["secret_value_matches"] is True, "读到的密钥与 .env 里的不一致"
    assert report["master_key_bytes"] == 32, "主密钥应当解出 32 字节"


def test_env_file_is_found_from_any_working_directory(probe_env: Path, tmp_path: Path) -> None:
    """路径按模块定位而不是靠 cwd：从别处启动 uvicorn 也要读到同一份 `.env`。"""
    outside = tmp_path / "somewhere-else"
    outside.mkdir()

    report = run_probe(probe_env, outside)

    assert report["env_file"] == str(PROJECT_ROOT / ".env")
    assert report["secret_value_matches"] is True


def test_real_environment_wins_over_env_file(probe_env: Path, tmp_path: Path) -> None:
    """真实环境变量优先于 `.env`：生产容器注入的密钥不能被文件盖掉。"""
    other = "another-secret-with-enough-length-000000"
    report = run_probe(
        probe_env,
        tmp_path,
        extra_env={"AI_INTERNAL_SECRET": other, "PROBE_EXPECTED": other},
    )

    assert report["secret_value_matches"] is True, "`.env` 覆盖了真实环境变量"
    # 非密钥字段同理：真实环境变量优先（这里 .env 写的是 8201）
    assert report["port"] == 8201, "Settings 仍应读 .env 的端口"


def test_missing_env_file_does_not_break_startup(tmp_path: Path) -> None:
    """没有 `.env` 也要能起来：默认值可用，缺密钥时是「拒绝相关能力」而不是启动失败。"""
    report = run_probe(tmp_path / "not-there.env", tmp_path)

    assert report["env_source"].endswith("（不存在，全部用默认值与环境变量）")
    assert report["port"] == 8200
    assert report["log_level"] == "INFO"
    assert report["secret_in_environ"] is False
    assert report["secret_error"] == "InternalAuthError", "缺密钥应当是明确的拒绝，不是别的异常"
