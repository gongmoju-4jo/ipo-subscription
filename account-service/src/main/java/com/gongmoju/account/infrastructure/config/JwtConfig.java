package com.gongmoju.account.infrastructure.config;

import com.gongmoju.account.domain.auth.AccessTokenIssuer;
import com.gongmoju.account.infrastructure.security.JwtAccessTokenIssuer;
import com.gongmoju.account.infrastructure.security.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfig {

    @Bean
    AccessTokenIssuer accessTokenIssuer(JwtProperties properties) {
        return new JwtAccessTokenIssuer(properties, Clock.systemUTC());
    }
}
