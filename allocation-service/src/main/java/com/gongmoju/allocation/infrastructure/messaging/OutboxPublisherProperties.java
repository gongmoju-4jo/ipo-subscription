package com.gongmoju.allocation.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Outbox 발행기 설정 (application.yml의 outbox.publisher.*). 서비스 3개가 같은 키를 쓴다.
 * 스케줄 주기는 {@link OutboxPublisher}의 {@code @Scheduled}가 같은 키(interval)를 직접 읽는다.
 *
 * @param batchSize      한 번에 조회해서 발행하는 최대 행 수
 * @param alertThreshold 이 횟수 이상 연속 실패하면 알림 로그를 남긴다 (발행은 계속 시도한다. Consumer 재시도와 별개)
 * @param sendTimeout    한 번의 반복에서 보낸 행들의 Kafka 발행 성공을 기다리는 최대 시간 (행마다가 아니라 전체)
 */
@ConfigurationProperties(prefix = "outbox.publisher")
public record OutboxPublisherProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("5") int alertThreshold,
        @DefaultValue("5s") Duration sendTimeout
) {
}
