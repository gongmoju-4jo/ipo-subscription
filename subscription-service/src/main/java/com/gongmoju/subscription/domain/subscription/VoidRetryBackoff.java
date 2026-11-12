package com.gongmoju.subscription.domain.subscription;

import java.time.Duration;

/**
 * 증거금 반환 재요청의 대기 시간 계산 (백오프). 마지막 요청 뒤 다음 재요청까지 기다리는 시간을 요청 횟수로 결정.
 * <p>
 * 대기 시간 = {@code min(initialInterval × 2^(attempts − 1), maxInterval)}. 기본값(30초, 5분 상한)이면 요청 횟수 1, 2, 3, 4, 5 이상일 때
 * 30초, 60초, 120초, 240초, 300초. 최대 횟수는 없음 (응답이 올 때까지 상한 간격으로 계속 재요청).
 * <p>
 * 조회({@code findStaleVoidIds})의 SQL 식과 같은 값을 내야 함 (같은 식을 SQL에 한 번 더 쓰므로 {@code SubscriptionStaleVoidQueryTest}가 두 결과가 같은지 확인함).
 * SQL 식의 지수 상한({@link #MAX_EXPONENT})과 요청 횟수 하한(1)도 동일.
 *
 * @param initialInterval 첫 재요청 대기 시간 (0보다 커야 함)
 * @param maxInterval     대기 시간의 상한 ({@code initialInterval} 이상이어야 함)
 */
public record VoidRetryBackoff(Duration initialInterval, Duration maxInterval) {

    // 2의 거듭제곱이 너무 커지지 않게 하는 안전장치. SQL 식의 LEAST(attempts, 20) − 1과 동일
    static final int MAX_EXPONENT = 19;

    public VoidRetryBackoff {
        if (initialInterval == null || initialInterval.isZero() || initialInterval.isNegative()) {
            throw new IllegalArgumentException("초기 대기 시간은 0보다 커야 합니다: " + initialInterval);
        }
        if (maxInterval == null || maxInterval.compareTo(initialInterval) < 0) {
            throw new IllegalArgumentException("최대 대기 시간은 초기 대기 시간 이상이어야 합니다: initial=" + initialInterval + ", max=" + maxInterval);
        }
    }

    /**
     * 요청 횟수가 {@code attempts}일 때, 마지막 요청 뒤 다음 재요청까지 기다리는 시간.
     * 보상을 시작하면 요청 횟수가 1이라 1 미만은 1로 봄 (SQL 식과 동일).
     */
    public Duration waitAfter(int attempts) {
        int exponent = Math.min(Math.max(attempts, 1) - 1, MAX_EXPONENT);
        long factor = 1L << exponent;
        long initialMillis = initialInterval.toMillis();
        long maxMillis = maxInterval.toMillis();
        // initial × factor > max 를 곱셈 없이 비교 (오버플로 방지)
        if (initialMillis > maxMillis / factor) {
            return maxInterval;
        }
        return Duration.ofMillis(initialMillis * factor);
    }

    /** SQL 식에 넘기는 초기 대기 시간 (초) */
    public double initialSeconds() {
        return initialInterval.toMillis() / 1000.0;
    }

    /** SQL 식에 넘기는 최대 대기 시간 (초) */
    public double maxSeconds() {
        return maxInterval.toMillis() / 1000.0;
    }
}
