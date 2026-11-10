"""一次性初始化生产管理员；密码只走 stdin，不写文件、日志或命令行。"""
import argparse
import getpass
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--retire-demo-accounts", action="store_true",
                        help="停用仍使用公开种子哈希的账号，保留其内容并使旧令牌失效")
    args = parser.parse_args()
    compose_dir = Path(__file__).resolve().parents[1] / "docker"
    command = ["docker", "compose", "run", "--rm", "--no-deps", "-T", "user-service",
               "java", "-Dloader.main=com.stellarink.user.bootstrap.AdminBootstrap",
               "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
    payload = None
    if args.retire_demo_accounts:
        command.append("--retire-demo-accounts")
    else:
        username = input("管理员登录名：").strip()
        password = getpass.getpass("管理员密码（16–64字符，UTF-8最多72字节）：")
        if password != getpass.getpass("再次输入密码："):
            raise SystemExit("两次密码不同，未执行初始化")
        if "\n" in username or "\r" in username or "\n" in password or "\r" in password:
            raise SystemExit("登录名或密码不能包含换行")
        payload = username + "\n" + password + "\n"
    subprocess.run(command, cwd=compose_dir, input=payload, text=True, encoding="utf-8", check=True)


if __name__ == "__main__":
    main()
