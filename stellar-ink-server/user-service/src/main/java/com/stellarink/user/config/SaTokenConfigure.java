package com.stellarink.user.config;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token JWT 无状态模式：token 自包含签名，网关与各服务用相同密钥独立验签
 */
@Configuration
public class SaTokenConfigure {

    @Bean
    public StpLogicJwtForStateless getStpLogic() {
        return new StpLogicJwtForStateless();
    }
}
