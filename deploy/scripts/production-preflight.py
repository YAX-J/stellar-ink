"""生产发布检查：拒绝演示初始化、默认凭据和缺失的安全迁移；不打印配置值。"""
import argparse
import base64
import importlib.util
from pathlib import Path
import subprocess


def check(values):
    errors = []
    if values.get("SPRING_PROFILES_ACTIVE", "prod") != "prod" or values.get("AI_APP_ENV", "prod") != "prod":
        errors.append("生产环境必须使用 prod 档位")
    for key in ("MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD", "SA_TOKEN_JWT_SECRET", "AI_INTERNAL_SECRET",
                "AI_SECRET_MASTER_KEY", "NACOS_PASSWORD", "NACOS_AUTH_TOKEN", "NACOS_AUTH_IDENTITY_KEY",
                "NACOS_AUTH_IDENTITY_VALUE", "GRAFANA_ADMIN_PASSWORD", "GATEWAY_CORS_ORIGINS"):
        value = values.get(key, "")
        if not value or value.lower() in {"nacos", "admin", "changeme", "change_me", "security", "serveridentity", "password"} or "${" in value:
            errors.append(key + " 缺失或使用默认值")
    jwt = values.get("SA_TOKEN_JWT_SECRET", "")
    if len(jwt) < 32 or jwt in {"stellar-ink-satoken-jwt-secret-32bytes", "SecretKey012345678901234567890123456789012345678901234567890123456789"}:
        errors.append("JWT 密钥长度不足或为公开示例")
    token = values.get("NACOS_AUTH_TOKEN", "")
    try:
        if len(base64.b64decode(token, validate=True)) < 32 or token.startswith("SecretKey0123"):
            errors.append("Nacos 认证密钥必须为随机生成的至少32字节 Base64")
    except ValueError:
        errors.append("Nacos 认证密钥不是有效 Base64")
    if "*" in values.get("GATEWAY_CORS_ORIGINS", ""):
        errors.append("生产 CORS 不允许通配来源")
    if "demo" in values.get("COMPOSE_FILE", ""):
        errors.append("生产禁止加载演示数据覆盖文件")
    return errors


def main():
    root = Path(__file__).resolve().parents[1] / "docker"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=root / ".env")
    parser.add_argument("--runtime", action="store_true", help="初始化完成后复核数据库与告警配置")
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("alerts_config", Path(__file__).with_name("configure-alerts.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    errors = check(module.load_env(args.env_file))
    if not errors and args.runtime:
        # 只适用于 selfcontained；宿主机数据库须按运行手册用专用客户端配置执行同等 SQL。
        query = ("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                 "AND TABLE_NAME='user' AND COLUMN_NAME='auth_version';"
                 "SELECT COUNT(*) FROM `user` WHERE role='ADMIN';"
                 "SELECT COUNT(*) FROM `user` WHERE password='$2b$10$/Y2gZHVQbuUI8VDF96YrDONvBeVPz4H44LX2bTrrTTGeRaf1nZN4a';")
        result = subprocess.run(["docker", "compose", "--env-file", str(args.env_file.resolve()),
                                 "-f", str(root / "docker-compose.yml"), "exec", "-T", "mysql", "sh", "-c",
                                 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names "$MYSQL_DATABASE"'],
                                cwd=root, input=query, text=True, capture_output=True)
        rows = result.stdout.split()
        if result.returncode or len(rows) != 3 or not all(row.isdigit() for row in rows):
            errors.append("运行库检查失败，请检查 MySQL 连接与表结构")
        elif rows[0] != "1" or int(rows[1]) < 1 or rows[2] != "0":
            errors.append("运行库缺少安全迁移/管理员，或仍存在公开口令账号")
        if not (root / "observability/alertmanager/alertmanager.yml").is_file():
            errors.append("尚未生成邮件告警配置")
    if errors:
        raise SystemExit("生产检查未通过：\n- " + "\n- ".join(errors))
    print("生产配置检查通过；不代表恢复演练或邮件送达已经验收。")


if __name__ == "__main__":
    main()
