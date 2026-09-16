package com.stellarink.sharedmodel.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 撤销令牌的 Redis 键规则；只保存摘要，避免 JWT 原文进入键空间和日志。 */
public final class TokenRevocationKey {

    private static final String PREFIX = "stellar-ink:auth:revoked:";

    private TokenRevocationKey() {
    }

    public static String of(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("令牌不能为空");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", ex);
        }
    }
}
