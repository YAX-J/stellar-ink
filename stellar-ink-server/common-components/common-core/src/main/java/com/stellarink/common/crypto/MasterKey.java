package com.stellarink.common.crypto;

import java.util.Base64;

/**
 * 主密钥读取：只认环境变量 {@code AI_SECRET_MASTER_KEY}（base64 的 32 字节）。
 *
 * <p>与 Python 侧 {@code app/core/crypto.py} 的 {@code load_master_key} 同口径：
 * 缺失或长度不足时抛 {@link MasterKeyMissingException}（调用方转成「配置缺失」提示，
 * 而不是 500），且**绝不在异常消息或日志里带出密钥内容**。
 */
public final class MasterKey {

    private MasterKey() {
    }

    /** 读取主密钥；未配置或格式不对时抛异常。 */
    public static byte[] load() {
        return load(System.getenv(AesGcmCipher.MASTER_KEY_ENV));
    }

    /** 读取主密钥（显式传入，便于测试与配置中心接入）。 */
    public static byte[] load(String rawValue) {
        String raw = rawValue == null ? "" : rawValue.trim();
        if (raw.isEmpty()) {
            throw new MasterKeyMissingException("未配置 " + AesGcmCipher.MASTER_KEY_ENV
                    + "（base64 编码的 32 字节），无法加密 API Key");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw new MasterKeyMissingException(
                    AesGcmCipher.MASTER_KEY_ENV + " 不是合法的 base64");
        }
        if (decoded.length < 32) {
            throw new MasterKeyMissingException(
                    AesGcmCipher.MASTER_KEY_ENV + " 解码后不足 32 字节");
        }
        byte[] key = new byte[32];
        System.arraycopy(decoded, 0, key, 0, 32);
        return key;
    }

    /** 主密钥是否已配置（用于面板展示「能否写入 Key」，不暴露密钥本身）。 */
    public static boolean configured() {
        String raw = System.getenv(AesGcmCipher.MASTER_KEY_ENV);
        return raw != null && !raw.isBlank();
    }

    /** 主密钥缺失或非法：属于「配置问题」，不应作为服务端错误上报。 */
    public static class MasterKeyMissingException extends RuntimeException {
        public MasterKeyMissingException(String message) {
            super(message);
        }
    }
}
