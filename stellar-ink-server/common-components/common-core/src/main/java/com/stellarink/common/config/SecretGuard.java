package com.stellarink.common.config;

import com.stellarink.sharedmodel.auth.JwtSecretPolicy;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动哨兵（Servlet 侧）：非 dev 档拒绝用「公开可见的密钥」或过短密钥启动。
 *
 * <p>规则本身在 {@link JwtSecretPolicy}（shared-model，网关与这里共用一份）。
 * 本类只负责「谁该被校验」与「把违规翻成启动失败」：
 * <ul>
 *   <li>先判本服务是否参与 JWT 验签：没声明 {@code sa-token.jwt-secret-key} 的服务
 *       （不引入 sa-token-jwt 的那些）直接跳过，避免对它们误报「未设置密钥」。</li>
 *   <li>再判档位：{@code dev} 放行（本机联调用仓库默认值），其余一律校验。</li>
 * </ul>
 *
 * <p>失败即抛异常终止启动（fail-closed），不做降级、不只在日志里告警。
 *
 * <p>为什么不能只靠「配置里不写默认值」：见 {@link JwtSecretPolicy} 的类注释 ——
 * 实测未注入环境变量时 Spring 会把 {@code ${SA_TOKEN_JWT_SECRET}} 这个**字面量**当密钥，
 * 服务照常启动、JCE 预热成功，等于密钥公开。所以这道校验是必需的，不是锦上添花。
 */
@Slf4j
@Component
public class SecretGuard {

    private final Environment environment;
    private final String jwtSecret;

    public SecretGuard(Environment environment,
                       @Value("${sa-token.jwt-secret-key:}") String jwtSecret) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
    }

    @PostConstruct
    public void verify() {
        // 先判「本服务是否参与 JWT 验签」：没声明该属性说明根本不用这个密钥，
        // 跳过校验，避免对它们误报「未设置密钥」而拒绝启动。
        if (!environment.containsProperty(JwtSecretPolicy.PROPERTY)) {
            log.debug("[SecretGuard] 本服务未声明 {}，不参与 JWT 验签，跳过校验。", JwtSecretPolicy.PROPERTY);
            return;
        }

        if (!JwtSecretPolicy.requiresCheck(environment.getActiveProfiles())) {
            log.info("[SecretGuard] 当前档位为 dev，放行仓库默认密钥（仅限本机联调，切勿用于对外实例）。");
            return;
        }

        String violation = JwtSecretPolicy.violation(jwtSecret);
        if (violation != null) {
            throw new IllegalStateException("[SecretGuard] " + violation + "，拒绝启动。"
                    + "请注入环境变量 SA_TOKEN_JWT_SECRET（生成方式：openssl rand -base64 48）。");
        }
        log.info("[SecretGuard] JWT 密钥校验通过（长度 {}）", jwtSecret.length());
    }
}
