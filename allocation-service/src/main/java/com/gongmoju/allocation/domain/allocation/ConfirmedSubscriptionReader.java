package com.gongmoju.allocation.domain.allocation;

import java.util.List;
import java.util.UUID;

// 배정 대상인 확정 청약 목록을 가져온다. 구현은 infrastructure/client (청약 서비스 Feign 호출)
public interface ConfirmedSubscriptionReader {

    /**
     * 공모주의 확정(CONFIRMED) 청약을 전부 가져온다. 일부만 받은 채로 돌려주지 않는다.
     *
     * @param ipoId 공모주 ID
     * @return 확정 청약 목록 (subscriptionId 순). 확정 청약이 없으면 빈 목록
     * @throws RuntimeException 가져오지 못하면 (청약 서비스 실패, 타임아웃, Circuit Breaker 열림, 받은 건수 불일치) 예외를 그대로 던진다
     */
    List<ConfirmedSubscription> readAll(UUID ipoId);
}
