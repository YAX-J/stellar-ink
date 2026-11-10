"""从本地私有配置生成 Alertmanager 邮件配置，SMTP 授权码不输出。"""
import argparse
import json
import os
from pathlib import Path
from urllib.parse import urlsplit


def load_env(path):
    values = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key.strip() in values:
            raise ValueError("配置格式错误或有重复键")
        value = value.strip()
        # 仅剥离成对外层引号，保留授权码中实际存在的引号字符。
        if len(value) >= 2 and value[0] in ('"', "'") and value[-1] == value[0]:
            value = value[1:-1]
        values[key.strip()] = value
    return values


def build_config(values):
    for key in ("SMTP_SMARTHOST", "SMTP_FROM", "SMTP_AUTH_USERNAME", "SMTP_AUTH_PASSWORD", "ALERT_EMAIL_TO"):
        if not values.get(key) or values[key].startswith("CHANGE_"):
            raise ValueError("请填写 " + key)
    smarthost = values["SMTP_SMARTHOST"]
    try:
        address = urlsplit("//" + smarthost)
        port = address.port
        if (not address.hostname or not port or address.username is not None
                or address.path or address.query or address.fragment or any(c.isspace() for c in smarthost)
                or smarthost.rsplit(":", 1)[-1] != str(port)):
            raise ValueError()
    except ValueError:
        raise ValueError("SMTP_SMARTHOST 需填写服务器:端口，例如 smtp.163.com:465") from None
    if port == 994:
        raise ValueError("当前 Alertmanager 仅在 465 端口直接建立 TLS，请将 163 邮箱地址填为 smtp.163.com:465")
    # v0.27 的 465 分支先执行 tls.Dial；此时关闭的是重复的 STARTTLS 升级，
    # TLS 加密和服务器证书校验仍然有效。其他端口始终强制 STARTTLS。
    require_starttls = port != 465
    return {
        "global": {"resolve_timeout": "5m", "smtp_smarthost": values["SMTP_SMARTHOST"],
                   "smtp_from": values["SMTP_FROM"], "smtp_auth_username": values["SMTP_AUTH_USERNAME"],
                   "smtp_auth_password": values["SMTP_AUTH_PASSWORD"], "smtp_require_tls": require_starttls},
        "route": {"receiver": "stellar-email", "group_by": ["alertname", "application", "instance"],
                  "group_wait": "30s", "group_interval": "1m", "repeat_interval": "4h"},
        "receivers": [{"name": "stellar-email", "email_configs": [{"to": values["ALERT_EMAIL_TO"], "send_resolved": True,
            "tls_config": {"min_version": "TLS12", "insecure_skip_verify": False}}]}],
    }


def main():
    root = Path(__file__).resolve().parents[1] / "docker"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=root / ".env.alerts")
    parser.add_argument("--output", type=Path, default=root / "observability/alertmanager/alertmanager.yml")
    args = parser.parse_args()
    config = build_config(load_env(args.env_file))
    os.umask(0o077)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(".tmp")
    temporary.write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.chmod(0o600)
    temporary.replace(args.output)
    print("邮件告警配置已生成，授权码未输出。下一步执行 amtool 校验及通知演练。")


if __name__ == "__main__":
    main()
