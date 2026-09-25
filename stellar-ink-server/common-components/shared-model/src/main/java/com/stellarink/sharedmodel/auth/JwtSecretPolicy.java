package com.stellarink.sharedmodel.auth;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * JWT 签名密钥的启动校验规则（**唯一实现**，common-core 与网关共同调用）。
 *
 * <p><b>为什么必须有它</b>（实测结论，别再写回「配置里没有默认值就等于 fail-fast」）：
 * 配置写成 {@code jwt-secret-key: ${SA_TOKEN_JWT_SECRET}} 时，环境变量缺失**并不会**让应用启动失败 ——
 * 实测 Spring 会把这个**字面量字符串** {@code ${SA_TOKEN_JWT_SECRET}} 原样当作密钥传下去：
 * 服务照常启动、JCE 预热成功、{@code /actuator/env} 里能直接看到该字面量。
 * 也就是说，凡读过本仓库的人都能用这个公开字符串自签一个带 {@code role} 的 token 冒充任意身份。
 * 所以「密钥到底能不能用」必须由代码在启动时判定，不能指望占位符解析替我们兜底。
 *
 * <p><b>适用范围</b>：{@code dev} 放行（本机联调要开箱即用，用的就是仓库里的公开默认值），
 * 其余档位（{@code test} / {@code prod} / 将来新增的）一律校验。判据是 active profile 里
 * 是否含 {@code dev}，而不是只认 {@code prod} —— 后者会让新增档位静默裸奔。
 *
 * <p><b>为什么不放 common-core</b>：网关是 WebFlux，不能依赖带 Servlet 的 common-core，
 * 而它同样验签、同样需要这道校验。放在 shared-model（两边都依赖、且本模块无 Spring 依赖）
 * 能让规则只有一份，两个入口类各自调用即可。
 */
public final class JwtSecretPolicy {

    /** 被校验的配置键 */
    public static final String PROPERTY = "sa-token.jwt-secret-key";

    /** 密钥最小长度 */
    public static final int MIN_LENGTH = 32;

    /**
     * 禁止使用的密钥。第一条是**最危险**的：未解析的占位符字面量，
     * 它意味着「环境变量根本没注入」，但因为不是空串，长度检查也拦不住（它正好 22 个字符，
     * 会被长度规则放过）—— 必须显式列在这里。
     */
    public static final Set<String> FORBIDDEN_SECRETS = Set.of(
            "${SA_TOKEN_JWT_SECRET}",
            "stellar-ink-satoken-jwt-secret-32bytes",
            "SecretKey012345678901234567890123456789012345678901234567890123456789",
            "changeme",
            "CHANGE_ME",
            "change-me");

    private JwtSecretPolicy() {
    }

    /**
     * 该运行档位是否需要校验密钥。
     *
     * <p>判据是「**全部** active profile 都属于 dev 系」才放行：`allMatch` 而不是 `noneMatch`
     * —— 否则 {@code --spring.profiles.active=dev,prod} 这种可疑组合会因为含 dev 而被放行，
     * 这正是最不该放过的情形。空数组/ null（未指定档位）同样从严。
     *
     * @param activeProfiles Spring 的 active profiles（可为 null / 空）
     * @return {@code true} 表示必须校验
     */
    public static boolean requiresCheck(String[] activeProfiles) {
        if (activeProfiles == null || activeProfiles.length == 0) {
            return true;
        }
        return !Arrays.stream(activeProfiles)
                .allMatch(profile -> profile != null
                        && profile.toLowerCase(Locale.ROOT).contains("dev"));
    }

    /**
     * 校验密钥是否可用。
     *
     * @param secret 生效的 {@code sa-token.jwt-secret-key} 值
     * @return 违规原因（一句给人看的中文说明）；合规返回 {@code null}
     */
    public static String violation(String secret) {
        if (secret == null || secret.isBlank()) {
            return "未设置 " + PROPERTY + "（环境变量 SA_TOKEN_JWT_SECRET 缺失或为空）";
        }
        if (FORBIDDEN_SECRETS.contains(secret)) {
            return PROPERTY + " 用的是公开可见的值（" + describe(secret) + "），任何人都能用它伪造令牌";
        }
        if (secret.length() < MIN_LENGTH) {
            return PROPERTY + " 长度不足 " + MIN_LENGTH + " 字符（当前 " + secret.length() + " 字符）";
        }
        return null;
    }

    private static String describe(String secret) {
        if ("${SA_TOKEN_JWT_SECRET}".equals(secret)) {
            return "未解析的占位符字面量，说明环境变量根本没注入";
        }
        return "仓库里的默认值或官方示例值";
    }
}
