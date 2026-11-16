package com.gongmoju.subscription.infrastructure.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 경쟁률 사본(Redis) 설정 (application.yml의 ipo.competition-rate.*).
 *
 * @param redisEnabled false면 Redis를 쓰지 않고 조회마다 DB로 합산한다 (Redis 없이 띄우는 테스트용)
 * @param ttl          사본의 유효 시간. 만료되면 다음 조회가 DB로 다시 계산한다. DB 커밋과 Redis 갱신이 한 트랜잭션이 아니라 생길 수 있는 오차를 이 시간 안에 바로잡는다
 * @param loadingTtl   채우는 중 표시의 유효 시간. 채우던 쪽(DB 합산 중인 요청)이 죽어도 이 시간이 지나면 다른 쪽이 채울 수 있다. DB 합산 시간보다 넉넉히
 * @param retryAfter   Redis 호출이 실패하면 이 시간 동안 Redis를 부르지 않는다 (장애 중에 확정 Consumer와 조회가 호출마다 응답 대기 시간을 기다리지 않게)
 */
@ConfigurationProperties(prefix = "ipo.competition-rate")
public record CompetitionRateProperties(
        @DefaultValue("true") boolean redisEnabled,
        @DefaultValue("PT5M") Duration ttl,
        @DefaultValue("PT30S") Duration loadingTtl,
        @DefaultValue("PT10S") Duration retryAfter
) {
}
