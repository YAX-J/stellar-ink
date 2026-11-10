"""向本机 Alertmanager 注入一次带结束时间的演练告警；用户需确认故障和恢复邮件均收到。"""
import datetime
import json
import urllib.request

now = datetime.datetime.now(datetime.timezone.utc)
payload = [{"labels": {"alertname": "StellarInkNotificationDrill", "severity": "warning", "application": "演练"},
            "annotations": {"summary": "星笺邮件通知演练", "description": "此为人工演练，请确认本邮件及约三分钟后的恢复邮件。"},
            "startsAt": now.isoformat(), "endsAt": (now + datetime.timedelta(minutes=3)).isoformat()}]
request = urllib.request.Request("http://127.0.0.1:9093/api/v2/alerts", data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
with urllib.request.urlopen(request, timeout=10) as response:
    print("演练已提交；HTTP 接收成功不代表邮件送达，请核对收件箱和恢复通知。")
