"""生产机拉取协作端：只列出加密备份，收到 Windows 校验回执后更新异地成功指标。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tempfile


NAME = re.compile(r"stellar-\d{8}T\d{6}Z\.tar\.age")


def describe(root, name):
    if not NAME.fullmatch(name):
        raise ValueError("备份文件名不合法")
    path = root / name
    checksum = root / (name + ".sha256")
    if path.is_symlink() or checksum.is_symlink() or not path.is_file():
        raise ValueError("备份缺失或包含链接")
    fields = checksum.read_text(encoding="utf-8").split()
    if len(fields) != 2 or not re.fullmatch(r"[0-9a-f]{64}", fields[0]) or fields[1] != name:
        raise ValueError("备份校验文件不合法")
    return dict(name=name, path=str(path), sha256=fields[0], size=path.stat().st_size,
                created_at=int(path.stat().st_mtime))


def acknowledge(root, metrics, name, checksum):
    info = describe(root, name)
    if info["sha256"] != checksum:
        raise ValueError("回执与备份校验值不一致")
    # 再核对源文件，避免清单损坏或传输期间源文件被改写后仍确认成功。
    with (root / name).open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != checksum:
            raise ValueError("源备份内容校验失败")
    metrics.mkdir(parents=True, exist_ok=True)
    metrics.chmod(0o755)
    target = metrics / "stellar_backup_last_success_timestamp_seconds.prom"
    previous = float(target.read_text().split()[1]) if target.exists() else 0
    # 用备份本身的时间，旧包今天才下载也不能伪装成今日新备份。
    timestamp = max(previous, info["created_at"])
    with tempfile.NamedTemporaryFile(mode="w", dir=metrics, prefix=".pull-", delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(f"stellar_backup_last_success_timestamp_seconds {timestamp:.0f}\n")
    try:
        temporary.chmod(0o644)
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=Path("/etc/stellar-ink/backup.env"))
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("latest")
    ack = sub.add_parser("ack")
    ack.add_argument("name")
    ack.add_argument("sha256")
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("config", Path(__file__).with_name("configure-alerts.py"))
    config = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(config)
    values = config.load_env(args.env_file)
    if values.get("BACKUP_DELIVERY_MODE") != "pull":
        raise ValueError("生产备份配置尚未启用 pull 模式")
    root = Path(values.get("BACKUP_ROOT", "/data/stellar-ink/backups"))
    metrics = Path(values.get("BACKUP_METRICS_DIR", str(Path(__file__).resolve().parents[1] / "docker/data/node-exporter")))
    if not root.is_absolute() or not metrics.is_absolute():
        raise ValueError("备份目录和指标目录必须为绝对路径")
    if args.command == "latest":
        names = sorted(p.name for p in root.glob("stellar-*.tar.age") if NAME.fullmatch(p.name)
                       and (root / (p.name + ".sha256")).is_file())
        if not names:
            raise ValueError("尚无可下载的加密备份，请先运行生产机备份任务")
        print(json.dumps(describe(root, names[-1])))
    else:
        acknowledge(root, metrics, args.name, args.sha256)
        print("Windows 加密备份校验回执已记录")


if __name__ == "__main__":
    main()
