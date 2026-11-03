package com.gongmoju.subscription.infrastructure.config;

import com.gongmoju.subscription.infrastructure.scheduling.IpoCloseProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 공모주 마감 스케줄러 설정. 스케줄링은 ipo.close.enabled=false로 끌 수 있다 (테스트는 마감 메서드를 직접 호출)
// Outbox 발행기와 따로 켜고 끈다. 한쪽을 꺼도 다른 쪽 스케줄은 돌아야 하기 때문
@Configuration
@EnableConfigurationProperties(IpoCloseProperties.class)
class IpoCloseConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "ipo.close", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
