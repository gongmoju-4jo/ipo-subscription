package com.gongmoju.subscription.domain.subscription;

import java.time.Instant;
import java.util.UUID;

/**
 * 청약 신청 접수 내용. {@code subscription.requested} 토픽으로 발행한다 (필드는 이벤트 명세 2-1).
 *
 * @param receivedAt Gateway가 기록한 최초 도착 시각 (D-12). 청약 가능 여부를 판단하는 기준
 */
public record SubscriptionRequested(
        UUID requestId,
        UUID subscriptionId,
        UUID userId,
        UUID accountId,
        UUID ipoId,
        long requestedQuantity,
        Instant receivedAt
) {
}
