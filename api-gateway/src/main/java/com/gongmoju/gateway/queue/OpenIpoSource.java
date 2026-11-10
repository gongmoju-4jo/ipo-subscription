package com.gongmoju.gateway.queue;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 청약 중(OPEN) 공모주 목록을 가져오는 곳. 실제 구현은 청약 서비스 내부 API를 부른다 ({@link SubscriptionOpenIpoSource}).
 */
interface OpenIpoSource {

    Mono<List<OpenIpo>> fetch();

    /**
     * 청약 중 공모주 한 건. 청약 기간은 시작 시각 이상, 마감 시각 미만이다 (청약 서비스의 청약 가능 시간과 같은 규칙).
     */
    record OpenIpo(UUID ipoId, Instant subscriptionStartAt, Instant subscriptionEndAt) {
    }
}
