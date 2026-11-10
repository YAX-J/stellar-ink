package com.stellarink.sharedmodel.auth;

/** JWT 中的账号版本；缺失版本的历史令牌必须重新登录。 */
public final class AuthVersion {
    public static final String JWT_KEY = "authVersion";

    private AuthVersion() { }

    public static boolean matches(Object claim, Long current) {
        if (claim == null || current == null || current < 0) return false;
        try {
            return Long.parseLong(claim.toString()) == current;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
