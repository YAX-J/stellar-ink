package com.stellarink.user.config;

import com.stellarink.common.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {

    @Bean
    public JwtUtil jwtUtil(@Value("${stellar.jwt.secret}") String secret,
                           @Value("${stellar.jwt.expire-hours:168}") long expireHours) {
        return new JwtUtil(secret, expireHours);
    }
}
