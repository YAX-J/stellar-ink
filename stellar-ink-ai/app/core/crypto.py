"""API Key 的静态加密（AES-256-GCM）。

设计要点（与 Java 侧 `AesGcmCipher` 必须逐字节一致）：

- 主密钥只从环境变量 ``AI_SECRET_MASTER_KEY``（base64 编码的 32 字节）读取，**永不入库、不进日志**。
- 密文格式：``v1:<nonce b64>:<ciphertext+tag b64>``，nonce 12 字节、tag 16 字节。
- 每次加密都用新的随机 nonce；GCM 的 tag 保证密文被篡改时解密直接失败。
- 主密钥缺失时**拒绝加密**（避免悄悄写入无法解密的密文）；解密已有密文仍照常进行，
  这样不会因为一次环境变量疏漏把已经配好的环境锁死。

跨语言一致性由 `tests/fixtures/crypto_vector.json` 里的固定向量保证：
Java 生成的密文在 Python 侧必须能解出同一明文，两边的单测都读这个文件。
"""

import base64
import binascii
import os

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MASTER_KEY_ENV = "AI_SECRET_MASTER_KEY"

_VERSION = "v1"
_NONCE_BYTES = 12
_KEY_BYTES = 32


class MasterKeyMissingError(RuntimeError):
    """主密钥未配置：调用方应给出可操作的提示，而不是抛 500。"""


class CipherFormatError(ValueError):
    """密文格式不合法：说明数据被截断或来自不兼容的版本。"""


class DecryptError(ValueError):
    """解密失败：主密钥不匹配，或密文被篡改。"""


def load_master_key(env: dict[str, str] | None = None) -> bytes:
    """读取并校验主密钥；缺失或长度不对时抛 :class:`MasterKeyMissingError`。"""
    source = os.environ if env is None else env
    raw = (source.get(MASTER_KEY_ENV) or "").strip()
    if not raw:
        raise MasterKeyMissingError(
            f"未配置 {MASTER_KEY_ENV}（base64 编码的 32 字节），无法加密 API Key",
        )
    try:
        key = base64.b64decode(raw, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise MasterKeyMissingError(f"{MASTER_KEY_ENV} 不是合法的 base64") from exc
    if len(key) < _KEY_BYTES:
        raise MasterKeyMissingError(f"{MASTER_KEY_ENV} 解码后不足 {_KEY_BYTES} 字节")
    return key[:_KEY_BYTES]


def encrypt(plaintext: str, *, key: bytes, nonce: bytes | None = None) -> str:
    """加密。``nonce`` 仅测试注入用，生产一律随机。"""
    if not plaintext:
        raise ValueError("待加密内容不能为空")
    if len(key) != _KEY_BYTES:
        raise ValueError(f"主密钥必须是 {_KEY_BYTES} 字节")
    iv = nonce if nonce is not None else os.urandom(_NONCE_BYTES)
    if len(iv) != _NONCE_BYTES:
        raise ValueError(f"nonce 必须是 {_NONCE_BYTES} 字节")
    sealed = AESGCM(key).encrypt(iv, plaintext.encode("utf-8"), None)  # 密文尾部自带 16 字节 tag
    return f"{_VERSION}:{_b64(iv)}:{_b64(sealed)}"


def decrypt(token: str, *, key: bytes) -> str:
    """解密；格式非法或认证失败都抛异常，绝不返回半截明文。"""
    version, iv, sealed = _split(token)
    if version != _VERSION:
        raise CipherFormatError(f"不支持的密文版本：{version}")
    try:
        return AESGCM(key).decrypt(iv, sealed, None).decode("utf-8")
    except InvalidTag as exc:
        raise DecryptError("密文认证失败：主密钥不匹配或数据被篡改") from exc


def mask(plaintext: str) -> str:
    """生成只用于回显的掩码，形如 ``sk-…9f3a``；不足以掩码时只留前两位。"""
    value = plaintext.strip()
    if len(value) <= 8:
        return value[:2] + "…" if value else ""
    return f"{value[:3]}…{value[-4:]}"


def _split(token: str) -> tuple[str, bytes, bytes]:
    parts = token.split(":")
    if len(parts) != 3:
        raise CipherFormatError("密文格式应为 v1:<nonce>:<ciphertext>")
    try:
        return parts[0], base64.b64decode(parts[1], validate=True), base64.b64decode(
            parts[2], validate=True
        )
    except (binascii.Error, ValueError) as exc:
        raise CipherFormatError("密文里的 nonce/ciphertext 不是合法 base64") from exc


def _b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")
