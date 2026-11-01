package com.gongmoju.allocation.infrastructure.config;

import com.gongmoju.allocation.infrastructure.messaging.OutboxPublisherProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Outbox 발행기 설정. 스케줄링은 outbox.publisher.enabled=false로 끌 수 있다 (테스트는 발행 메서드를 직접 호출)
@Configuration
@EnableConfigurationProperties(OutboxPublisherProperties.class)
class OutboxPublisherConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "outbox.publisher", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
