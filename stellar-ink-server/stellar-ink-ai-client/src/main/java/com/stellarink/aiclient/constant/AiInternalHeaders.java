package com.stellarink.aiclient.constant;

/**
 * Java 与 Python 之间的内部请求头常量（HMAC 内网签名协议）。
 *
 * <p>协议要点（实现见 M1「Java/Python 安全调用链」）：
 * 身份与权限**只**由这些头传给 Python，Python 不解析 Sa-Token、不读业务表。
 * 签名覆盖 方法 + 路径 + 时间戳 + nonce + body 摘要，密钥取环境变量 {@code AI_INTERNAL_SECRET}。
 *
 * <p>本类只放常量：任何签名计算逻辑都不属于 M0 的工作范围。
 */
public final class AiInternalHeaders {

    /** 链路追踪 ID，与网关/Java 日志共用同一个值 */
    public static final String TRACE_ID = "X-Trace-Id";

    /** 业务用户 ID（由 ai-service 从 Sa-Token 取出后填入，浏览器不可伪造） */
    public static final String USER_ID = "X-AI-User-Id";

    /** 用户角色（READER / AUTHOR / ADMIN） */
    public static final String ROLE = "X-AI-Role";

    /** 请求时间戳（毫秒，Unix epoch），用于时间窗校验 */
    public static final String TIMESTAMP = "X-AI-Timestamp";

    /** 一次性随机串，用于防重放 */
    public static final String NONCE = "X-AI-Nonce";

    /** HMAC-SHA256 签名（小写十六进制） */
    public static final String SIGNATURE = "X-AI-Signature";

    private AiInternalHeaders() {
    }
}
