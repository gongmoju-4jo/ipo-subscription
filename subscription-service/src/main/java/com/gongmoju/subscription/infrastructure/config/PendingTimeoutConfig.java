package com.gongmoju.subscription.infrastructure.config;

import com.gongmoju.subscription.infrastructure.scheduling.PendingTimeoutProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 청약의 응답 시간 초과 처리 설정 값 등록.
 * 스케줄링은 saga.timeout.enabled=false로 비활성화 가능 (테스트는 처리 메서드를 직접 호출)
 * Outbox 발행기, 공모주 마감 스케줄러와 따로 활성화.
 */
@Configuration
@EnableConfigurationProperties(PendingTimeoutProperties.class)
class PendingTimeoutConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "saga.timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
