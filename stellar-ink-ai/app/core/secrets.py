"""密钥与配置：API Key 的静态加密、面板掩码、跨语言一致性。

`crypto` 里的格式必须与 Java 侧 `com.stellarink.common.crypto.AesGcmCipher` 完全一致，
由 `tests/fixtures/crypto_vector.json` 的固定向量守住（两侧单测都读它）。
"""

from app.core.crypto import (
    MASTER_KEY_ENV,
    CipherFormatError,
    DecryptError,
    MasterKeyMissingError,
    decrypt,
    encrypt,
    load_master_key,
    mask,
)

__all__ = [
    "MASTER_KEY_ENV",
    "CipherFormatError",
    "DecryptError",
    "MasterKeyMissingError",
    "decrypt",
    "encrypt",
    "load_master_key",
    "mask",
]
