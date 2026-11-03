package com.gongmoju.subscription.infrastructure.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 응답 시간 초과 처리 설정 (application.yml의 saga.timeout.*).
 * 실행 간격(interval)은 {@link PendingTimeoutScheduler}의 {@code @Scheduled}가 동일한 키를 직접 읽고,
 * 활성화(enabled)는 조건부 설정에서 별도로 읽음.
 *
 * @param responseTimeout 마지막 인출 요청 후 시간 초과로 판단하기까지의 대기 시간 (이벤트 명세 3-1, 4장 위험 4: 30초 확정)
 * @param maxAttempts     최초 요청을 포함한 최대 인출 요청 횟수.(임시값)
 * @param batchSize       한 번에 처리하는 청약 최대 건수
 */
@ConfigurationProperties(prefix = "saga.timeout")
public record PendingTimeoutProperties(
        @DefaultValue("PT30S") Duration responseTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("100") int batchSize
) {

    public PendingTimeoutProperties {
        if (responseTimeout.isZero() || responseTimeout.isNegative()) {
            throw new IllegalArgumentException("saga.timeout.response-timeout은 0보다 커야 합니다: " + responseTimeout);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("saga.timeout.max-attempts는 1 이상이어야 합니다: " + maxAttempts);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("saga.timeout.batch-size는 1 이상이어야 합니다: " + batchSize);
        }
    }
}
