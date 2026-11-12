package com.gongmoju.subscription.infrastructure.scheduling;

import com.gongmoju.subscription.domain.subscription.VoidRetryBackoff;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 증거금 반환 재요청 설정 (application.yml의 saga.void-retry.*).
 * 인출 응답 시간 초과 설정({@link PendingTimeoutProperties}, saga.timeout.*)과 따로 둠. 인출은 최대 횟수에 이르면 보상으로 넘어가지만,
 * 반환은 최대 횟수 없이 응답이 올 때까지 간격을 늘려 가며 재요청하므로 정하는 값이 다름.
 * 실행 간격(saga.timeout.interval), 사용 여부(saga.timeout.enabled), 알림 간격(saga.timeout.alert-interval)은 인출 설정과 같이 씀.
 *
 * @param initialInterval 첫 재요청 대기 시간. 요청 횟수가 늘 때마다 2배
 * @param maxInterval     재요청 대기 시간 상한.
 * @param alertAttempts   요청 횟수가 해당 값 이상일 때 응답 없으면 알림 로그 대상 (기본값이면 보상 시작 약 7분 30초 뒤, 임시값)
 * @param batchSize       한 번에 처리하는 청약 최대 건수
 */
@ConfigurationProperties(prefix = "saga.void-retry")
public record VoidRetryProperties(
        @DefaultValue("PT30S") Duration initialInterval,
        @DefaultValue("PT5M") Duration maxInterval,
        @DefaultValue("5") int alertAttempts,
        @DefaultValue("100") int batchSize
) {

    public VoidRetryProperties {
        if (initialInterval.isZero() || initialInterval.isNegative()) {
            throw new IllegalArgumentException("saga.void-retry.initial-interval은 0보다 커야 합니다: " + initialInterval);
        }
        if (maxInterval.compareTo(initialInterval) < 0) {
            throw new IllegalArgumentException("saga.void-retry.max-interval은 initial-interval 이상이어야 합니다: initial="
                    + initialInterval + ", max=" + maxInterval);
        }
        if (alertAttempts < 1) {
            throw new IllegalArgumentException("saga.void-retry.alert-attempts는 1 이상이어야 합니다: " + alertAttempts);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("saga.void-retry.batch-size는 1 이상이어야 합니다: " + batchSize);
        }
    }

    /** 반환 재요청 대기 시간 계산 */
    public VoidRetryBackoff backoff() {
        return new VoidRetryBackoff(initialInterval, maxInterval);
    }
}
