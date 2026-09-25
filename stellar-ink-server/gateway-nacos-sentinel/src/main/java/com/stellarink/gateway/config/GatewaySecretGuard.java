package com.stellarink.gateway.config;

import com.stellarink.sharedmodel.auth.JwtSecretPolicy;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动哨兵（网关侧）：非 dev 档拒绝用「公开可见的密钥」或过短密钥启动。
 *
 * <p>为什么网关要单独有一份：网关是 WebFlux 应用，**不能依赖带 Servlet 的 common-core**，
 * 因此拿不到那边的 {@code SecretGuard}。而网关恰恰是最需要这道校验的服务 ——
 * 它自己用 {@code StpUtil.checkLogin()} 独立验签，还把 JWT 里的 {@code role} 当门槛依据，
 * 密钥一旦是公开可见的字面量（如未注入环境变量时的 {@code ${SA_TOKEN_JWT_SECRET}}），
 * 任何人都能自签一个 {@code role=ADMIN} 的 token 直接通过网关。
 *
 * <p>规则本身不在这里：{@link JwtSecretPolicy} 位于 shared-model（网关与 common-core 都依赖），
 * 两个入口类共用同一份规则，避免「改一处忘了另一处」。改判定请只改那一个类，并同步两侧的单测。
 */
@Slf4j
@Component
public class GatewaySecretGuard {

    private final Environment environment;
    private final String jwtSecret;

    public GatewaySecretGuard(Environment environment,
                              @Value("${sa-token.jwt-secret-key:}") String jwtSecret) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
    }

    @PostConstruct
    public void verify() {
        // 网关一定参与验签，故没有 common-core 那边「未声明属性就跳过」的分支：
        // 这里没有密钥就是配置缺失，必须拦下。
        if (!JwtSecretPolicy.requiresCheck(environment.getActiveProfiles())) {
            log.info("[GatewaySecretGuard] 当前档位为 dev，放行仓库默认密钥（仅限本机联调，切勿用于对外实例）。");
            return;
        }

        String violation = JwtSecretPolicy.violation(jwtSecret);
        if (violation != null) {
            throw new IllegalStateException("[GatewaySecretGuard] " + violation + "，拒绝启动。"
                    + "请注入环境变量 SA_TOKEN_JWT_SECRET（生成方式：openssl rand -base64 48），"
                    + "并确认它与各业务服务的取值一致。");
        }
        log.info("[GatewaySecretGuard] JWT 密钥校验通过（长度 {}）", jwtSecret.length());
    }
}
