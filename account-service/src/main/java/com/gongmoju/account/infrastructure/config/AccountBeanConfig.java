package com.gongmoju.account.infrastructure.config;

import com.gongmoju.account.domain.account.AccountNumberGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 도메인 계층은 스프링을 모르므로, 도메인 서비스 객체는 여기서 빈으로 등록한다
@Configuration
class AccountBeanConfig {

    @Bean
    AccountNumberGenerator accountNumberGenerator() {
        return new AccountNumberGenerator();
    }
}
