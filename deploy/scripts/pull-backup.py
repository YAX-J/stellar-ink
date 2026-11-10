"""Windows 主动下载生产加密备份；私钥仅保存在电脑，不上传、不覆盖旧密钥。

先执行 init，再把公钥上传到生产机并配置 BACKUP_DELIVERY_MODE=pull。
SSH 使用系统已验证的 known_hosts 与密钥认证；不会关闭主机密钥检查。
"""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile


NAME = re.compile(r"stellar-\d{8}T\d{6}Z\.tar\.age")


def protect(directory):
    if os.name == "nt":
        result = subprocess.check_output(["whoami", "/user", "/fo", "csv", "/nh"], text=True)
        sid = next(csv.reader(io.StringIO(result)))[-1]
        if not re.fullmatch(r"S-\d+(?:-\d+)+", sid):
            raise ValueError("无法识别当前 Windows 用户 SID")
        subprocess.run(["icacls", str(directory), "/inheritance:r", "/grant:r", f"*{sid}:(OI)(CI)F"],
                       check=True, stdout=subprocess.DEVNULL)
    else:
        directory.chmod(0o700)


def initialize(directory, keygen):
    # 只创建新目录，避免改变已有用户文件权限或覆盖唯一恢复私钥。
    directory.mkdir(parents=True, exist_ok=False)
    protect(directory)
    identity = directory / "recovery-key.txt"
    subprocess.run([keygen, "-o", str(identity)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    public = subprocess.check_output([keygen, "-y", str(identity)], text=True).strip()
    (directory / "backup-recipients.txt").write_text(public + "\n", encoding="utf-8")
    print(f"密钥已生成。只上传公钥：{directory / 'backup-recipients.txt'}")
    print("recovery-key.txt 是解密私钥，务必另存一份到安全位置，不上传服务器。")


def validate_info(info):
    if not NAME.fullmatch(info.get("name", "")) or not re.fullmatch(r"[0-9a-f]{64}", info.get("sha256", "")):
        raise ValueError("服务器返回的备份元数据无效")
    if (type(info.get("size")) is not int or info["size"] <= 0
            or not isinstance(info.get("path"), str) or not info["path"].startswith("/")
            or info["path"].rsplit("/", 1)[-1] != info["name"] or "\n" in info["path"]):
        raise ValueError("服务器返回的备份路径或大小无效")


def download(info, directory, ssh, age, acknowledge):
    validate_info(info)
    identity = directory / "recovery-key.txt"
    if not identity.is_file():
        raise ValueError("缺少解密私钥，请先 init；不要在丢失原私钥时覆盖生成新密钥")
    if shutil.disk_usage(directory).free < info["size"] + 64 * 1024 * 1024:
        raise ValueError("Windows 磁盘空间不足，停止下载")
    destination = directory / info["name"]
    descriptor, temporary_name = tempfile.mkstemp(dir=directory, prefix=".pull-", suffix=".partial")
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            subprocess.run(ssh + ["cat -- " + shlex.quote(info["path"])], stdout=stream, check=True)
        with temporary.open("rb") as stream:
            checksum = hashlib.file_digest(stream, "sha256").hexdigest()
        if temporary.stat().st_size != info["size"] or checksum != info["sha256"]:
            raise ValueError("下载不完整或校验失败，未发送成功回执")
        # 验证整个密文可认证解密，明文直接丢弃，不写盘、不输出任何口令。
        subprocess.run([age, "--decrypt", "-i", str(identity), str(temporary)],
                       stdout=subprocess.DEVNULL, check=True)
        if destination.exists():
            with destination.open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != checksum:
                    raise ValueError("已有同名备份内容不同，拒绝覆盖")
        else:
            temporary.replace(destination)
        (directory / (info["name"] + ".sha256")).write_text(checksum + "  " + info["name"] + "\n", encoding="utf-8")
        acknowledge(info["name"], checksum)
        print(f"加密备份已下载、校验并确认：{destination}")
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path(__file__).resolve().parents[2] / ".backups")
    sub = parser.add_subparsers(dest="command", required=True)
    init = sub.add_parser("init")
    init.add_argument("--age-keygen", default="age-keygen")
    pull = sub.add_parser("pull")
    pull.add_argument("--host", required=True, help="SSH 配置别名，或 user@服务器地址")
    pull.add_argument("--port", type=int, default=22)
    pull.add_argument("--ssh-identity", type=Path, help="SSH 登录私钥，和 age 恢复私钥不同")
    pull.add_argument("--repo", default="/app/stellar-ink")
    pull.add_argument("--age", default="age")
    args = parser.parse_args()
    os.umask(0o077)
    if args.command == "init":
        initialize(args.directory.resolve(), args.age_keygen)
        return
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.@-]*", args.host) or not 1 <= args.port <= 65535:
        raise ValueError("SSH 地址或端口不合法")
    ssh = ["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=10",
           "-o", "ServerAliveInterval=30", "-o", "ServerAliveCountMax=3", "-p", str(args.port)]
    if args.ssh_identity:
        ssh.extend(["-i", str(args.ssh_identity)])
    ssh.append(args.host)
    helper = "python3 " + shlex.quote(args.repo.rstrip("/") + "/deploy/scripts/backup-pull-server.py")
    info = json.loads(subprocess.check_output(ssh + [helper + " latest"], text=True))
    def acknowledge(name, checksum):
        subprocess.run(ssh + [helper + " ack " + shlex.quote(name) + " " + shlex.quote(checksum)], check=True)
    download(info, args.directory.resolve(), ssh, args.age, acknowledge)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit("备份拉取失败：" + str(error)) from None
