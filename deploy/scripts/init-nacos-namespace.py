"""创建独立测试命名空间；不迁移配置、不修改现有实例与生产空间。

使用当前终端的 NACOS_ADDR / NACOS_USERNAME / NACOS_PASSWORD，默认只连接本机。
python deploy/scripts/init-nacos-namespace.py
"""

import json
import os
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError


def main() -> None:
    address = os.environ.get("NACOS_ADDR", "127.0.0.1:8848")
    base = address if address.startswith(("http://", "https://")) else "http://" + address
    base = base.rstrip("/") + "/nacos"
    query = ""
    try:
        with urlopen(base + "/v1/console/namespaces", timeout=5) as response:
            existing = json.load(response)["data"]
    except HTTPError as error:
        if error.code not in (401, 403):
            raise SystemExit("读取 Nacos 命名空间失败：HTTP " + str(error.code)) from None
        credentials = urlencode({
            "username": os.environ.get("NACOS_USERNAME", "nacos"),
            "password": os.environ.get("NACOS_PASSWORD", "nacos"),
        }).encode()
        with urlopen(Request(base + "/v1/auth/login", data=credentials), timeout=5) as response:
            token = json.load(response)["accessToken"]
        query = urlencode({"accessToken": token})
        with urlopen(base + "/v1/console/namespaces?" + query, timeout=5) as response:
            existing = json.load(response)["data"]
    if any(value["namespace"] == "stellar-ink-test" for value in existing):
        print("测试命名空间 stellar-ink-test 已存在")
        return
    body = urlencode({"customNamespaceId": "stellar-ink-test", "namespaceName": "stellar-ink-test",
                      "namespaceDesc": "星笺独立测试环境"}).encode()
    with urlopen(Request(base + "/v1/console/namespaces?" + query, data=body), timeout=5) as response:
        if response.read().strip() != b"true":
            raise SystemExit("创建测试命名空间失败，请检查 Nacos 权限")
    print("已创建命名空间 stellar-ink-test；本机与测试机使用各自的 Nacos，生产空间保持原值")


if __name__ == "__main__":
    main()
