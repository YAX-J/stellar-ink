package com.stellarink.ai.config;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token JWT 无状态模式：与网关、其它业务服务使用同一密钥独立验签。
 *
 * <p>**这个类不是可选的**：不注册它，Sa-Token 就停在默认的 StpLogic 上，
 * 此时 {@code StpUtil.getExtra(Role.JWT_KEY)} 会抛
 * 「只有在集成 sa-token-jwt 插件后才可以使用 extra 扩展参数」——
 * 结果是任何走 {@code AuthHelper} 的角色判断都变成 500，而不是 403。
 * 网关与 user/content 服务都有同样的配置，ai-service 也必须有一份。
 */
@Configuration
public class SaTokenConfigure {

    @Bean
    public StpLogicJwtForStateless getStpLogic() {
        return new StpLogicJwtForStateless();
    }
}
