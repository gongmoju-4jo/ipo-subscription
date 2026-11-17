package com.gongmoju.gateway.login;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 로그인 시도 제한 설정 (application.yml의 login-attempt.*).
 *
 * @param maxFailures   이 횟수만큼 실패하면 차단한다
 * @param blockDuration 실패를 세는 기간(첫 실패부터)이자, 최대 횟수에 닿은 때부터의 차단 시간
 * @param redisTimeout  Redis 응답을 기다리는 시간. 넘으면 횟수 확인 없이 로그인을 통과시킨다 (Redis 지연이 로그인 지연으로 번지지 않게)
 * @param redisSkip     Redis 실패 뒤 이 시간 동안은 Redis를 부르지 않고 통과시킨다 (장애 중 로그인마다 시간 초과를 기다리지 않게)
 */
@ConfigurationProperties(prefix = "login-attempt")
public record LoginAttemptProperties(
        @DefaultValue("5") int maxFailures,
        @DefaultValue("PT15M") Duration blockDuration,
        @DefaultValue("PT0.2S") Duration redisTimeout,
        @DefaultValue("PT5S") Duration redisSkip
) {

    public LoginAttemptProperties {
        if (maxFailures <= 0) {
            throw new IllegalArgumentException("login-attempt.max-failures는 0보다 커야 합니다: " + maxFailures);
        }
        requirePositive(blockDuration, "login-attempt.block-duration");
        requirePositive(redisTimeout, "login-attempt.redis-timeout");
        if (redisSkip == null || redisSkip.isNegative()) {
            throw new IllegalArgumentException("login-attempt.redis-skip는 0 이상이어야 합니다: " + redisSkip);
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "는 0보다 커야 합니다: " + value);
        }
    }
}
