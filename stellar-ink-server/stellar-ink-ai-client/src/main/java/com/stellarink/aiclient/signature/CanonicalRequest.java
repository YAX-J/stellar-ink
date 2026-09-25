package com.stellarink.aiclient.signature;

import com.stellarink.aiclient.constant.AiInternalHeaders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 内部请求的标准串（canonical request）：签名与被验签双方**必须逐字节一致**。
 *
 * <p>把它单独提出来，是为了让「签名方」与「验签方（Python）」对着同一段代码/文档实现，
 * 而不是各自凭记忆拼字符串 —— 那种做法只要有一处分隔符或大小写不同，就变成
 * 「本地测试全过、联调永远 401」。
 *
 * <pre>
 * canonical = METHOD + "\n"
 *           + PATH + "\n"
 *           + TIMESTAMP + "\n"
 *           + NONCE + "\n"
 *           + SHA256_HEX(BODY) + "\n"
 *           + USER_ID + "\n"
 *           + ROLE
 * </pre>
 *
 * 约定与理由：
 * <ul>
 *   <li>METHOD 用**大写**，避免 GET/get 两种写法产生两个签名。</li>
 *   <li>PATH 用不含 query 的请求路径（如 {@code /qa/stream}）；query 目前不参与签名，
 *       若将来需要把 query 一并纳入，必须两侧同时升级 —— 所以下面 {@code canonical}
 *       显式接收 path 而不是 URI，防止有人顺手把整串 URL 传进来。</li>
 *   <li>TIMESTAMP 是 **Unix 毫秒**字符串，配合 {@code X-AI-Timestamp} 的时间窗校验。</li>
 *   <li>NONCE 是一次性随机串，服务端按「时间窗内不重复」拒绝重放。</li>
 *   <li>BODY 摘要是**小写十六进制**的 SHA-256；空 body 用空串的摘要（不是空字符串），
 *       这样「无 body」与「body 为空串」不会被混为一谈。</li>
 *   <li><b>USER_ID 与 ROLE 必须参与签名</b>：只签 body 的话，内网里能改包的一方
 *       把 {@code X-AI-User-Id} 改成 1（ADMIN）依然能通过校验 —— 那是
 *       「验签通过但身份是别人」，比不验签更危险。</li>
 * </ul>
 */
public final class CanonicalRequest {

    /** 字段分隔符：换行。任何字段自身都不允许出现换行（下方有校验）。 */
    private static final String SEPARATOR = "\n";

    /** 空请求体的摘要占位：SHA-256("") 的十六进制 */
    public static final String EMPTY_BODY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private CanonicalRequest() {
    }

    /**
     * 组装标准串。
     *
     * @param method     HTTP 方法（大小写不敏感）
     * @param path       不含 query 的请求路径，需以 / 开头
     * @param timestampMs Unix 毫秒时间戳
     * @param nonce      一次性随机串（建议 32 位十六进制）
     * @param bodySha256 请求体摘要（小写十六进制），无 body 时传 {@link #EMPTY_BODY_SHA256}
     * @param userId     业务用户 id（正整数，由 ai-service 从 Sa-Token 取出）
     * @param role       用户角色（白名单内的枚举名）
     */
    public static String of(String method,
                            String path,
                            long timestampMs,
                            String nonce,
                            String bodySha256,
                            long userId,
                            String role) {
        String normalizedMethod = requireText(method, "method").toUpperCase(java.util.Locale.ROOT);
        String normalizedPath = requirePath(path);
        String normalizedNonce = requireText(nonce, "nonce");
        String normalizedDigest = requireText(bodySha256, "bodySha256").toLowerCase(java.util.Locale.ROOT);
        String normalizedRole = requireText(role, "role").toUpperCase(java.util.Locale.ROOT);
        if (timestampMs <= 0) {
            throw new IllegalArgumentException("timestampMs 必须是正的 Unix 毫秒时间戳");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 必须是正整数");
        }
        if (!"READER".equals(normalizedRole) && !"AUTHOR".equals(normalizedRole) && !"ADMIN".equals(normalizedRole)) {
            throw new IllegalArgumentException("role 必须是 READER/AUTHOR/ADMIN 之一：" + role);
        }
        return String.join(SEPARATOR,
                normalizedMethod,
                normalizedPath,
                Long.toString(timestampMs),
                normalizedNonce,
                normalizedDigest,
                Long.toString(userId),
                normalizedRole);
    }

    /** 计算请求体摘要（小写十六进制）；body 为 null 或空按空串处理。 */
    public static String sha256Hex(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(body == null ? new byte[0] : body);
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // JDK 一定带 SHA-256；真出现说明运行环境被人改过，属于致命配置问题
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", e);
        }
    }

    /** 字符串请求体的摘要（统一 UTF-8，避免平台默认编码差异）。 */
    public static String sha256Hex(String body) {
        return sha256Hex(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    /** 生成一次性随机串：32 位十六进制（16 字节随机）。 */
    public static String newNonce() {
        byte[] raw = new byte[16];
        new java.security.SecureRandom().nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (value.contains(SEPARATOR)) {
            // 字段里混入换行会让「两个不同请求」产生同一个标准串，等于给重放留后门
            throw new IllegalArgumentException(field + " 不能包含换行符");
        }
        return value;
    }

    private static String requirePath(String path) {
        String value = requireText(path, "path");
        if (!value.startsWith("/")) {
            throw new IllegalArgumentException("path 必须以 / 开头，且不含 query 与主机名：" + value);
        }
        if (value.contains("?")) {
            throw new IllegalArgumentException("path 不能带 query（query 暂不参与签名）：" + value);
        }
        return value;
    }
}
