"""使用真实 Alertmanager 和本机 TLS SMTP 验证通知，不连接真实邮箱。

依赖 openssl 与部署同版本的 Alertmanager 可执行文件；示例：
python3 deploy/scripts/test-alerts-smtp.py --alertmanager /path/to/alertmanager
"""
import argparse
import base64
import datetime
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import socketserver
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request


spec = importlib.util.spec_from_file_location("alerts", Path(__file__).with_name("configure-alerts.py"))
alerts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alerts)


class SmtpServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, context):
        self.context = context
        self.messages = []
        self.authenticated = threading.Event()
        super().__init__(("127.0.0.1", 465), SmtpHandler)


class SmtpHandler(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with self.server.context.wrap_socket(self.request, server_side=True) as connection:
                connection.settimeout(5)
                with connection.makefile("rwb") as stream:
                    def send(line):
                        stream.write(line + b"\r\n")
                        stream.flush()
                    send(b"220 local-smtp-test")
                    while line := stream.readline():
                        command = line.upper()
                        if command.startswith((b"EHLO", b"HELO")):
                            # 故意不宣告 STARTTLS，证明 465 不会再次升级 TLS。
                            send(b"250-local-smtp-test")
                            send(b"250 AUTH PLAIN")
                        elif command.startswith(b"AUTH PLAIN "):
                            expected = b"\0drill\0test-only-secret"
                            if base64.b64decode(line.split()[2]) != expected:
                                send(b"535 authentication failed")
                                continue
                            self.server.authenticated.set()
                            send(b"235 authenticated")
                        elif command.startswith((b"MAIL FROM", b"RCPT TO")):
                            send(b"250 OK")
                        elif command.startswith(b"DATA"):
                            send(b"354 end with dot")
                            message = bytearray()
                            while (part := stream.readline()) not in (b".\r\n", b""):
                                message.extend(part)
                            self.server.messages.append(bytes(message))
                            send(b"250 accepted")
                        elif command.startswith(b"QUIT"):
                            send(b"221 goodbye")
                            break
                        else:
                            send(b"502 unsupported")
        except (ssl.SSLError, OSError):
            # 不信任测试证书时客户端会在认证之前主动断开。
            pass


def wait_until(predicate, message):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        try:
            if predicate():
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.1)
    raise AssertionError(message)


def run_case(executable, root, cert, context, trusted):
    values = dict(SMTP_SMARTHOST="127.0.0.1:465", SMTP_FROM="drill@example.invalid",
                  SMTP_AUTH_USERNAME="drill", SMTP_AUTH_PASSWORD="test-only-secret",
                  ALERT_EMAIL_TO="drill@example.invalid")
    config = alerts.build_config(values)
    config["route"].update(group_wait="0s", group_interval="1s")
    if trusted:
        config["receivers"][0]["email_configs"][0]["tls_config"]["ca_file"] = str(cert)
    config_path = root / "alertmanager.json"
    config_path.write_text(json.dumps(config), encoding="utf-8")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    address = f"http://127.0.0.1:{port}"
    with SmtpServer(context) as server, (root / "alertmanager.log").open("w") as log:
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        process = subprocess.Popen([str(executable), "--config.file=" + str(config_path),
            "--storage.path=" + str(root / "data"), "--cluster.listen-address=",
            "--web.listen-address=127.0.0.1:" + str(port)], stdout=log, stderr=log,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            def fetch(path):
                with urllib.request.urlopen(address + path, timeout=2) as response:
                    return response.read().decode()
            wait_until(lambda: fetch("/-/ready") == "OK", "Alertmanager 未就绪")
            now = datetime.datetime.now(datetime.timezone.utc)
            alert = {"labels": {"alertname": "LocalSmtpDrill", "severity": "warning"},
                     "startsAt": now.isoformat(), "endsAt": (now + datetime.timedelta(minutes=5)).isoformat()}
            def submit():
                request = urllib.request.Request(address + "/api/v2/alerts", data=json.dumps([alert]).encode(),
                    headers={"Content-Type": "application/json"}, method="POST")
                with urllib.request.urlopen(request, timeout=2):
                    pass
            submit()
            if trusted:
                wait_until(lambda: len(server.messages) >= 1, "未收到告警邮件")
                assert server.authenticated.is_set()
                assert b"FIRING" in server.messages[0]
                alert["endsAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
                submit()
                wait_until(lambda: len(server.messages) >= 2, "未收到恢复邮件")
                assert b"RESOLVED" in server.messages[-1]
                print("通过：真实 Alertmanager 在 465 先建立 TLS，认证并发送故障与恢复邮件")
            else:
                def failure_recorded():
                    metrics = fetch("/metrics")
                    return any(float(value) > 0 for value in re.findall(
                        r'alertmanager_notifications_failed_total\{[^\n]*integration="email"[^\n]*\} ([\d.e+]+)', metrics))
                wait_until(failure_recorded, "未记录不受信任证书的发送失败")
                assert not server.authenticated.is_set() and not server.messages
                print("通过：不受信任的证书被拒绝，授权码未进入 SMTP 认证")
        finally:
            process.terminate()
            process.wait(timeout=10)
            server.shutdown()
            worker.join(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--alertmanager", type=Path, required=True)
    parser.add_argument("--openssl", default="openssl")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="stellar-smtp-") as tmp:
        root = Path(tmp)
        cert, key = root / "cert.pem", root / "key.pem"
        subprocess.run([args.openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
            "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1",
            "-keyout", str(key), "-out", str(cert)], check=True, capture_output=True)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(cert, key)
        for trusted in (True, False):
            directory = root / ("trusted" if trusted else "untrusted")
            directory.mkdir()
            run_case(args.alertmanager.resolve(), directory, cert, context, trusted)


if __name__ == "__main__":
    main()
