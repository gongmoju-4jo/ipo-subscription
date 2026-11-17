package com.gongmoju.gateway.login;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LoginAttemptProperties.class)
class LoginAttemptConfig {
}
