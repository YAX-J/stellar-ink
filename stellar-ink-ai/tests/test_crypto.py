"""跨语言加解密一致性：Java 写的密文，Python 必须能读。

基准文件 `tests/fixtures/key_vector.json` 由 Java 侧 `VectorBootstrapper` 生成，
两侧单测都读它。这样任何一侧改了算法、密文格式或掩码规则，两侧会同时失败 ——
这是「面板在 Java 写、模型调用在 Python 读」这条链路唯一可靠的保护方式。
"""

import base64
import json
from pathlib import Path
from typing import Any

import pytest

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

FIXTURES = Path(__file__).parent / "fixtures"


def load_vector() -> dict[str, Any]:
    with (FIXTURES / "key_vector.json").open(encoding="utf-8") as handle:
        data: dict[str, Any] = json.load(handle)
    return data


def test_vector_is_generated() -> None:
    ciphertext = load_vector()["ciphertext"]

    assert "PENDING_GENERATION" not in ciphertext, (
        "向量还没生成，请先在 stellar-ink-server 下跑 VectorBootstrapper"
    )


def test_decrypts_ciphertext_written_by_java() -> None:
    vector = load_vector()

    plaintext = decrypt(
        vector["ciphertext"],
        key=load_master_key({MASTER_KEY_ENV: vector["masterKeyB64"]}),
    )

    assert plaintext == vector["plaintext"], "Java 写的密文 Python 解不开：两侧格式已经不一致"


def test_encryption_matches_frozen_vector() -> None:
    vector = load_vector()

    token = encrypt(
        vector["plaintext"],
        key=load_master_key({MASTER_KEY_ENV: vector["masterKeyB64"]}),
        nonce=base64.b64decode(vector["nonceB64"]),
    )

    assert token == vector["ciphertext"], "Python 加密结果与 Java 固化密文不一致"


def test_tampered_ciphertext_is_rejected() -> None:
    vector = load_vector()

    with pytest.raises(DecryptError):
        decrypt(
            vector["tamperedCiphertext"],
            key=load_master_key({MASTER_KEY_ENV: vector["masterKeyB64"]}),
        )


def test_wrong_key_is_rejected() -> None:
    vector = load_vector()

    with pytest.raises(DecryptError):
        decrypt(
            vector["ciphertext"],
            key=load_master_key({MASTER_KEY_ENV: vector["wrongKeyB64"]}),
        )


def test_nonce_is_random_per_encryption() -> None:
    key = load_master_key({MASTER_KEY_ENV: load_vector()["masterKeyB64"]})
    plaintext = "sk-live-0123456789abcdef"

    first = encrypt(plaintext, key=key)
    second = encrypt(plaintext, key=key)

    assert first != second, "两次加密用了同一个 nonce，GCM 的安全性直接失效"
    assert decrypt(first, key=key) == plaintext
    assert decrypt(second, key=key) == plaintext


def test_mask_matches_java_rules() -> None:
    vector = load_vector()

    assert mask(vector["plaintext"]) == vector["masked"]
    assert "stellar" not in mask(vector["plaintext"])
    assert mask("abcdef") == "ab…"
    assert mask("") == ""


def test_malformed_ciphertext_is_reported() -> None:
    key = b"\x00" * 32

    with pytest.raises(CipherFormatError):
        decrypt("not-a-token", key=key)
    with pytest.raises(CipherFormatError):
        decrypt("v2:AAAA:BBBB", key=key)
    with pytest.raises(CipherFormatError):
        decrypt("v1:!!!:BBBB", key=key)


def test_master_key_problems_are_actionable() -> None:
    with pytest.raises(MasterKeyMissingError):
        load_master_key({})
    with pytest.raises(MasterKeyMissingError):
        load_master_key({MASTER_KEY_ENV: "   "})
    with pytest.raises(MasterKeyMissingError):
        load_master_key({MASTER_KEY_ENV: "not base64!!"})
    with pytest.raises(MasterKeyMissingError):
        load_master_key({MASTER_KEY_ENV: base64.b64encode(b"short").decode()})
