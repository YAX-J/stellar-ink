package com.stellarink.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 生产启动哨兵：prod 环境下拒绝用「仓库中公开可见的默认密钥」或过短密钥启动。
 *
 * <p>为什么需要它：dev 配置为了让服务开箱即用，给 {@code sa-token.jwt-secret-key}
 * 留了默认值，而该值随代码提交进仓库、等于公开。prod 配置本身没有默认值（缺失即启动失败），
 * 但若运维把环境变量显式设成同一个值（或设成空串），就能被任何人离线伪造 token
 * —— 本类正是拦住这种情况。
 *
 * <p><b>适用范围</b>：只有真正参与 Sa-Token JWT 验签的服务才持有 JWT 密钥，即
 * gateway（{@code StpUtil.checkLogin()} 独立验签）与 user-service（{@code AuthHelper.loginId()} 验签）。
 * post / meteor / echo / link / stats 五个服务不引入 {@code sa-token-jwt}、也不解析 token，
 * 配置中不存在 {@code sa-token.jwt-secret-key}，本类对它们直接跳过（见 {@link #verify()}）。
 * 判定依据是「是否声明了该属性」而非硬编码服务名：将来新增需要验签的服务，
 * 只要按 gateway/user-service 的样式配上密钥，就会自动纳入校验。
 *
 * <p>失败即抛异常终止启动（fail-closed），不做降级、不只在日志里告警。
 */
@Slf4j
@Component
public class SecretGuard {

    /** 被校验的属性名 */
    private static final String PROPERTY = "sa-token.jwt-secret-key";

    /** 禁止在 prod 使用的密钥：仓库 dev 默认值 + Nacos 官方文档示例值 */
    private static final Set<String> FORBIDDEN_SECRETS = Set.of(
            "stellar-ink-satoken-jwt-secret-32bytes",
            "SecretKey012345678901234567890123456789012345678901234567890123456789",
            "changeme", "CHANGE_ME", "change-me"
    );

    private static final int MIN_LENGTH = 32;

    private final Environment environment;
    private final String jwtSecret;

    public SecretGuard(Environment environment,
                       @Value("${sa-token.jwt-secret-key:}") String jwtSecret) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
    }

    @PostConstruct
    public void verify() {
        // 先判「本服务是否参与 JWT 验签」：没声明该属性说明根本不用这个密钥
        // （如 post/meteor/echo/link/stats），跳过校验，避免对它们误报「未设置密钥」拒绝启动。
        if (!environment.containsProperty(PROPERTY)) {
            log.debug("[SecretGuard] 本服务未声明 {}，不参与 JWT 验签，跳过校验。", PROPERTY);
            return;
        }

        boolean prod = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.toLowerCase(Locale.ROOT).contains("prod"));
        if (!prod) {
            return;
        }

        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "[SecretGuard] prod 环境未设置 " + PROPERTY + "，拒绝启动。"
                            + "请设置环境变量 SA_TOKEN_JWT_SECRET（生成方式：openssl rand -base64 48）。");
        }
        if (FORBIDDEN_SECRETS.contains(jwtSecret)) {
            throw new IllegalStateException(
                    "[SecretGuard] prod 环境检测到 " + PROPERTY + " 使用了仓库中公开的默认值，拒绝启动。"
                            + "请设置环境变量 SA_TOKEN_JWT_SECRET（生成方式：openssl rand -base64 48）。");
        }
        if (jwtSecret.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "[SecretGuard] prod 环境 " + PROPERTY + " 长度不足 " + MIN_LENGTH + " 字符，拒绝启动。");
        }
        log.info("[SecretGuard] JWT 密钥校验通过（长度 {}）", jwtSecret.length());
    }
}
