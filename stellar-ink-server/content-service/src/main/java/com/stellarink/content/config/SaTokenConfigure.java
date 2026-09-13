package com.stellarink.content.config;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token JWT 无状态模式：与网关、用户服务使用同一密钥独立验签。
 */
@Configuration
public class SaTokenConfigure {

    @Bean
    public StpLogicJwtForStateless getStpLogic() {
        return new StpLogicJwtForStateless();
    }
}
