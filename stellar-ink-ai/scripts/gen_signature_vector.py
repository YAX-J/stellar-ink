"""生成内部请求签名的跨语言基准向量（Java 侧与 Python 侧都读它）。

用 Python 标准库直接算出「标准串 → 摘要 → 签名」，写成 JSON 供两侧单测断言。
这不是测试脚本，而是**一次性生成器**：向量生成后由两侧测试共同守住，
格式一变两侧同时红。重新生成：``uv run python scripts/gen_signature_vector.py``。
"""

from __future__ import annotations

import hashlib
import hmac
import json
from pathlib import Path

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console

FIXTURE = Path(__file__).resolve().parent.parent / "tests" / "fixtures" / "signature_vector.json"


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def canonical(
    method: str,
    path: str,
    timestamp_ms: int,
    nonce: str,
    body_sha256: str,
    user_id: int,
    role: str,
) -> str:
    """与 Java `CanonicalRequest.of` 必须逐字节一致。

    注意身份（userId/role）**也在签名里**：否则内网中间人改一个头就能冒充 ADMIN。
    """
    return "\n".join(
        [method.upper(), path, str(timestamp_ms), nonce, body_sha256, str(user_id), role.upper()]
    )


def main() -> None:
    data = json.loads(FIXTURE.read_text(encoding="utf-8"))
    secret = data["secret"].encode("utf-8")
    data["bodySha256Empty"] = sha256_hex("")
    default_identity = data.get("identity", {"userId": 42, "role": "AUTHOR"})

    for case in data["cases"]:
        identity = case.get("identity", default_identity)
        body_sha = sha256_hex(case["body"])
        text = canonical(
            case["method"],
            case["path"],
            case["timestampMs"],
            case["nonce"],
            body_sha,
            int(identity["userId"]),
            identity["role"],
        )
        case["identity"] = {"userId": int(identity["userId"]), "role": identity["role"].upper()}
        case["bodySha256"] = body_sha
        case["canonical"] = text
        case["signature"] = hmac.new(secret, text.encode("utf-8"), hashlib.sha256).hexdigest()

    FIXTURE.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for case in data["cases"]:
        print(f"{case['name']}: {case['signature']}")


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    main()
