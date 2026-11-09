package com.gongmoju.gateway.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(QueueProperties.class)
class QueueConfig {

    /**
     * 입장 스케줄러를 켠다. queue.scheduler.enabled=false는 테스트에서 스케줄러가 Redis를 부르지 않게 하는 값이다 (대기열을 끄는 설정 아님).
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "queue.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
