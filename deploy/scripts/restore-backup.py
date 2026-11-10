"""解密并验证到全新目录；不覆盖运行数据，恢复 JWT 自动换钥。"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import shutil
import subprocess
import tarfile
import tempfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def manifest(root):
    files = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError("备份不接受符号链接")
        if path.is_file() and path.relative_to(root).as_posix() != "manifest.json":
            files[path.relative_to(root).as_posix()] = digest(path)
    (root / "manifest.json").write_text(json.dumps({"version": 1, "files": files}, ensure_ascii=False), encoding="utf-8")


def extract_verified(archive, destination):
    destination.mkdir(mode=0o700, parents=True, exist_ok=False)
    with tarfile.open(archive, "r:gz") as tar:
        seen = set()
        for member in tar:
            name = PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts or "\\" in member.name or ":" in member.name:
                raise ValueError("归档路径不安全")
            if not (member.isdir() or member.isfile()):
                raise ValueError("归档包含链接或特殊文件")
            target = destination.joinpath(*name.parts)
            if member.isdir():
                target.mkdir(mode=0o700, parents=True, exist_ok=True)
                continue
            if str(name) in seen:
                raise ValueError("归档包含重复文件")
            seen.add(str(name))
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            with tar.extractfile(member) as source, target.open("xb") as output:
                shutil.copyfileobj(source, output)
            target.chmod(0o600)
    record = json.loads((destination / "manifest.json").read_text(encoding="utf-8"))
    actual = {p.relative_to(destination).as_posix(): digest(p)
              for p in destination.rglob("*") if p.is_file() and p.relative_to(destination).as_posix() != "manifest.json"}
    if record.get("version") != 1 or record.get("files") != actual:
        raise ValueError("备份清单校验失败")
    if not {"env.backup", "mysql.sql.gz"}.issubset(actual):
        raise ValueError("备份缺少数据库或密钥配置")
    original = (destination / "env.backup").read_text(encoding="utf-8")
    if len(re.findall(r"^SA_TOKEN_JWT_SECRET=.*$", original, re.MULTILINE)) != 1:
        raise ValueError("配置必须恰好包含一条 SA_TOKEN_JWT_SECRET")
    restored = re.sub(r"^SA_TOKEN_JWT_SECRET=.*$", "SA_TOKEN_JWT_SECRET=" + secrets.token_hex(32), original, flags=re.MULTILINE)
    (destination / ".env").write_text(restored, encoding="utf-8")
    (destination / ".env").chmod(0o600)
    (destination / "RESTORE-NOTES.txt").write_text(
        "清单已验证，JWT 已换钥。部署只使用 .env，不可使用 env.backup。\n"
        "先恢复数据库和文件，再同时重建全部 Java 服务。AI 加密主密钥未改变。\n"
        "尚未导入数据库；完整流程见 docs/architecture/production-p0.md。\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    build = sub.add_parser("manifest")
    build.add_argument("directory", type=Path)
    restore = sub.add_parser("restore")
    restore.add_argument("archive", type=Path)
    restore.add_argument("--identity", required=True, type=Path)
    restore.add_argument("--destination", required=True, type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    if args.command == "manifest":
        manifest(args.directory)
        return
    if args.destination.exists():
        raise SystemExit("恢复目录必须不存在，拒绝覆盖现有数据")
    args.destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".restore-", dir=args.destination.parent) as temp:
        decrypted = Path(temp) / "backup.tar.gz"
        subprocess.run(["age", "--decrypt", "-i", str(args.identity), "-o", str(decrypted), str(args.archive)], check=True)
        staged = Path(temp) / "verified"
        extract_verified(decrypted, staged)
        staged.rename(args.destination)
    print("备份已验证并暂存；JWT 已换钥，数据库导入尚未执行。")


if __name__ == "__main__":
    main()
