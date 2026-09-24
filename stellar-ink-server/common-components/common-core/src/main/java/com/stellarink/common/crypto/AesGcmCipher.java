package com.stellarink.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 的静态加密（AES-256-GCM）。
 *
 * <p><b>与 Python 侧 {@code app/core/crypto.py} 必须逐字节一致</b>，因为前端在 Java 侧写、
 * Python 侧要读同一份密文。格式：{@code v1:<nonce b64>:<ciphertext+tag b64>}
 * （nonce 12 字节、tag 16 字节，AES-GCM 默认把 tag 附在密文尾部）。
 * 两侧单测都读 {@code stellar-ink-ai/tests/fixtures/crypto_vector.json} 的固定向量。
 *
 * <p>为什么主密钥只从环境变量读：它一旦进库或进配置文件，就等于「密钥的密钥」和密文放在一起，
 * 加密形同虚设。缺失时**拒绝加密**（避免写入无法解密的密文），但解密已有密文照常进行，
 * 免得一次环境变量疏漏把已配好的环境锁死。
 */
public final class AesGcmCipher {

    /** 主密钥环境变量名（base64 编码的 32 字节） */
    public static final String MASTER_KEY_ENV = "AI_SECRET_MASTER_KEY";

    private static final String VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private AesGcmCipher() {
    }

    /** 加密：每次都用新的随机 nonce。 */
    public static String encrypt(String plaintext, byte[] key) {
        return encrypt(plaintext, key, randomNonce());
    }

    /** 加密（指定 nonce，仅供测试向量使用；生产请用 {@link #encrypt(String, byte[])}）。 */
    public static String encrypt(String plaintext, byte[] key, byte[] nonce) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("待加密内容不能为空");
        }
        requireKey(key);
        requireNonce(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + ":" + ENCODER.encodeToString(nonce) + ":" + ENCODER.encodeToString(sealed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    /**
     * 解密。
     *
     * @throws CipherFormatException 密文格式不合法或版本不支持
     * @throws DecryptException      认证失败（主密钥不匹配或密文被篡改）
     */
    public static String decrypt(String token, byte[] key) {
        requireKey(key);
        String[] parts = split(token);
        byte[] nonce = decodeBase64(parts[1], "nonce");
        byte[] sealed = decodeBase64(parts[2], "ciphertext");
        requireNonce(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new DecryptException("密文认证失败：主密钥不匹配或数据被篡改");
        }
    }

    /** 生成只用于面板回显的掩码，形如 {@code sk-…9f3a}；与 Python 的 {@code mask} 同规则。 */
    public static String mask(String plaintext) {
        if (plaintext == null) {
            return "";
        }
        String value = plaintext.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return value.substring(0, Math.min(2, value.length())) + "…";
        }
        return value.substring(0, 3) + "…" + value.substring(value.length() - 4);
    }

    private static String[] split(String token) {
        if (token == null) {
            throw new CipherFormatException("密文为空");
        }
        String[] parts = token.split(":", -1);
        if (parts.length != 3) {
            throw new CipherFormatException("密文格式应为 v1:<nonce>:<ciphertext>");
        }
        if (!VERSION.equals(parts[0])) {
            throw new CipherFormatException("不支持的密文版本：" + parts[0]);
        }
        return parts;
    }

    private static byte[] decodeBase64(String value, String field) {
        try {
            return DECODER.decode(value);
        } catch (IllegalArgumentException e) {
            throw new CipherFormatException("密文里的 " + field + " 不是合法 base64");
        }
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("主密钥必须是 " + KEY_BYTES + " 字节");
        }
    }

    private static void requireNonce(byte[] nonce) {
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce 必须是 " + NONCE_BYTES + " 字节");
        }
    }

    private static byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    /** 密文格式错误（可修复：数据被截断或版本不兼容）。 */
    public static class CipherFormatException extends RuntimeException {
        public CipherFormatException(String message) {
            super(message);
        }
    }

    /** 解密失败（认证不过：主密钥不匹配或数据被篡改）。 */
    public static class DecryptException extends RuntimeException {
        public DecryptException(String message) {
            super(message);
        }
    }
}
