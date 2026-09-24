package com.stellarink.aiclient.signature;

import com.stellarink.aiclient.constant.AiInternalHeaders;
import com.stellarink.sharedmodel.enums.Role;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java → Python 内部请求的 HMAC-SHA256 签名。
 *
 * <p>为什么需要它：Python 不解析 Sa-Token，身份与权限只能由 Java 传过来。
 * 但**下游服务在内网不等于可信** —— 编排网络里任何能发起 HTTP 的进程都能伪造
 * {@code X-AI-User-Id}。签名把「必须持有 {@code AI_INTERNAL_SECRET}」变成伪造前提，
 * 再加上时间窗与 nonce，让截获的请求无法原样重放。
 *
 * <p>密钥只从环境变量 {@code AI_INTERNAL_SECRET} 读，且**无默认值**：缺失时拒绝签名
 * （而不是用一个弱默认值继续跑）。与 {@code AI_SECRET_MASTER_KEY}（加密 Key 用）分开，
 * 两者泄露的影响面不同，不该共用一把。
 *
 * <p>签名内容见 {@link CanonicalRequest}。签名结果是**小写十六进制**，
 * 放在 {@code X-AI-Signature} 头里。
 */
public final class InternalRequestSigner {

    /** 内部签名密钥的环境变量名 */
    public static final String SECRET_ENV = "AI_INTERNAL_SECRET";

    /** 密钥最小长度：短于 32 字符的密钥在 HMAC 场景下没有必要冒险 */
    public static final int MIN_SECRET_LENGTH = 32;

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    private InternalRequestSigner(byte[] secret) {
        this.secret = secret;
    }

    /** 从环境变量创建；缺失或过短时抛 {@link SecretMissingException}。 */
    public static InternalRequestSigner fromEnvironment() {
        return fromSecret(System.getenv(SECRET_ENV));
    }

    /** 用给定密钥创建（便于测试与将来接入密钥管理服务）。 */
    public static InternalRequestSigner fromSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new SecretMissingException(
                    "未配置 " + SECRET_ENV + "，无法对内部请求签名（生成方式：openssl rand -base64 48）");
        }
        if (secret.trim().length() < MIN_SECRET_LENGTH) {
            throw new SecretMissingException(
                    SECRET_ENV + " 长度不足 " + MIN_SECRET_LENGTH + " 字符，请换一个更长的随机密钥");
        }
        return new InternalRequestSigner(secret.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** 内部签名密钥是否已配置（面板/探活据此提示「AI 能力是否可用」）。 */
    public static boolean configured() {
        String raw = System.getenv(SECRET_ENV);
        return raw != null && raw.trim().length() >= MIN_SECRET_LENGTH;
    }

    /**
     * 计算签名。
     *
     * @param canonical 由 {@link CanonicalRequest#of} 组装的标准串
     * @return 小写十六进制签名
     */
    public String sign(String canonical) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 算法一定存在；真失败说明 JCE 被裁剪，属于环境问题
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    /**
     * 直接为一次请求生成全部内部头（含 traceId 透传）。
     *
     * <p>返回的是**不可变副本**：调用方直接塞进 Feign 的 RequestInterceptor 即可，
     * 不必自己拼头名（拼错头名会导致 Python 侧验签失败，且很难看出来）。
     *
     * @param method     HTTP 方法
     * @param path       不含 query 的路径
     * @param body       请求体（可为 null）
     * @param userId     业务用户 id（由 ai-service 从 Sa-Token 取出）
     * @param role       用户角色
     * @param traceId    链路追踪 id，便于两侧日志对读
     */
    public Map<String, String> signRequest(String method,
                                           String path,
                                           String body,
                                           Long userId,
                                           Role role,
                                           String traceId) {
        return signRequest(method, path, body, userId, role, traceId, System.currentTimeMillis(),
                CanonicalRequest.newNonce());
    }

    /** 指定时间戳与 nonce 的重载：生产不用（每次随机），测试用它固定输入。 */
    public Map<String, String> signRequest(String method,
                                           String path,
                                           String body,
                                           Long userId,
                                           Role role,
                                           String traceId,
                                           long timestampMs,
                                           String nonce) {
        if (userId == null) {
            // 未登录请求不该带内部身份头：宁可失败，也不要用一个空 id 让下游误判
            throw new IllegalArgumentException("userId 不能为空：内部请求必须携带已确认的业务身份");
        }
        String normalizedRole = (role == null ? Role.READER : role).name();
        String canonical = CanonicalRequest.of(method, path, timestampMs, nonce,
                CanonicalRequest.sha256Hex(body), userId, normalizedRole);
        String signature = sign(canonical);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(AiInternalHeaders.TRACE_ID, traceId == null ? "" : traceId);
        headers.put(AiInternalHeaders.USER_ID, Long.toString(userId));
        headers.put(AiInternalHeaders.ROLE, normalizedRole);
        headers.put(AiInternalHeaders.TIMESTAMP, Long.toString(timestampMs));
        headers.put(AiInternalHeaders.NONCE, nonce);
        headers.put(AiInternalHeaders.SIGNATURE, signature);
        return Map.copyOf(headers);
    }

    /** 密钥缺失或不合格：属于「配置问题」，调用方应给出可操作提示而不是 500。 */
    public static class SecretMissingException extends RuntimeException {
        public SecretMissingException(String message) {
            super(message);
        }
    }
}
