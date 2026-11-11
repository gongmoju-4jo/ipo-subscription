package com.gongmoju.gateway.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 청약 중 공모주 목록 설정 (application.yml의 queue.open-ipos.*). 대기열 진입 때 청약 중인 공모주인지 확인하려고
 * 청약 서비스의 내부 API(API 명세 4.4)에서 목록을 주기적으로 받아 둔다.
 *
 * @param baseUrl        청약 서비스 주소. 기본은 Eureka 서비스 이름
 * @param loadBalanced   Eureka 서비스 이름으로 찾아 부를지 (테스트에서 가짜 서버를 직접 부를 때만 false)
 * @param checkInterval  목록을 갱신할 때가 됐는지 보는 주기. 받지 못했거나 갱신에 실패하면 이 주기로 다시 시도한다
 * @param refreshInterval 목록을 받은 뒤 다시 받기까지의 간격
 * @param timeout        한 번 받는 데 기다리는 시간
 * @param staleAfter     이 시간 동안 갱신하지 못한 목록은 쓰지 않는다 (확인 없이 진입 허용)
 */
@ConfigurationProperties(prefix = "queue.open-ipos")
public record OpenIpoProperties(
        @DefaultValue("http://subscription-service") String baseUrl,
        @DefaultValue("true") boolean loadBalanced,
        @DefaultValue("PT1S") Duration checkInterval,
        @DefaultValue("PT5S") Duration refreshInterval,
        @DefaultValue("PT2S") Duration timeout,
        @DefaultValue("PT30S") Duration staleAfter
) {

    public OpenIpoProperties {
        requirePositive(checkInterval, "queue.open-ipos.check-interval");
        requirePositive(refreshInterval, "queue.open-ipos.refresh-interval");
        requirePositive(timeout, "queue.open-ipos.timeout");
        requirePositive(staleAfter, "queue.open-ipos.stale-after");
        if (staleAfter.compareTo(refreshInterval) <= 0) {
            throw new IllegalArgumentException("queue.open-ipos.stale-after는 refresh-interval보다 커야 합니다: " + staleAfter);
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "는 0보다 커야 합니다: " + value);
        }
    }
}
